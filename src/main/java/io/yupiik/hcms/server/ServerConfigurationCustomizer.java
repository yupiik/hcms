/*
 * Copyright (c) 2024 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.hcms.server;

import static java.net.http.HttpClient.Redirect.ALWAYS;
import static java.time.Clock.systemUTC;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import io.yupiik.fusion.http.server.api.WebServer;
import io.yupiik.fusion.http.server.impl.tomcat.TomcatWebServerConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.tracing.collector.AccumulatingSpanCollector;
import io.yupiik.fusion.tracing.id.IdGenerator;
import io.yupiik.fusion.tracing.server.ServerTracingConfiguration;
import io.yupiik.fusion.tracing.server.TracingValve;
import io.yupiik.fusion.tracing.span.Span;
import io.yupiik.fusion.tracing.zipkin.ZipkinFlusher;
import io.yupiik.fusion.tracing.zipkin.ZipkinFlusherConfiguration;
import io.yupiik.hcms.configuration.TracingConfiguration;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.AbstractProtocol;

@ApplicationScoped
public class ServerConfigurationCustomizer {
    private ScheduledExecutorService scheduler;

    @Bean
    protected IdGenerator idGenerator() {
        return new IdGenerator(IdGenerator.Type.HEX);
    }

    public void configuration(
            @OnEvent final WebServer.Configuration webConf,
            final JsonMapper jsonMapper,
            final TracingConfiguration tracingConfiguration,
            final IdGenerator idGenerator) {
        final var configuration = webConf.unwrap(TomcatWebServerConfiguration.class);
        configuration.setTomcatCustomizers(List.of(this::customizeTomcat));
        configuration.setContextCustomizers(
                List.of(c -> customizeContext(c, jsonMapper, idGenerator, tracingConfiguration)));
    }

    @Destroy
    protected void destroy() {
        if (scheduler != null) {
            scheduler.close();
        }
    }

    private void customizeContext(
            final Context ctx,
            final JsonMapper jsonMapper,
            final IdGenerator idGenerator,
            final TracingConfiguration tracingConfiguration) {
        final var logger = Logger.getLogger(getClass().getName());
        if (!tracingConfiguration.enabled()
                || tracingConfiguration.urls() == null
                || tracingConfiguration.urls().isEmpty()) {
            logger.info(() -> "Tracing is not enabled.");
            return;
        }

        logger.info(() -> "Tracing is not enabled on " + tracingConfiguration.urls() + ".");

        final var zipkinFlusherConfiguration = new ZipkinFlusherConfiguration()
                .setHeaders(Map.of())
                .setTimeout(Duration.ofMillis(tracingConfiguration.requestTimeout()))
                .setUrls(tracingConfiguration.urls());
        final var builder = HttpClient.newBuilder()
                .followRedirects(ALWAYS)
                .connectTimeout(Duration.ofMillis(tracingConfiguration.connectTimeout()));
        if (tracingConfiguration.skipTLSChecks()) {
            final var currentValue = System.getProperty("jdk.internal.httpclient.disableHostnameVerification");
            if (!Boolean.parseBoolean(currentValue)) {
                System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
            }

            try {
                final var sslContext = SSLContext.getInstance("TLS");
                sslContext.init(
                        null,
                        new TrustManager[] {
                            new X509TrustManager() {
                                @Override
                                public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
                                    // no-op
                                }

                                @Override
                                public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
                                    // no-op
                                }

                                @Override
                                public X509Certificate[] getAcceptedIssuers() {
                                    return null;
                                }
                            }
                        },
                        new SecureRandom());
                builder.sslContext(sslContext);
            } catch (final GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
        final var flusher = new ZipkinFlusher(jsonMapper, builder.build(), zipkinFlusherConfiguration);

        final var collector = new AccumulatingSpanCollector(tracingConfiguration.bufferSize());
        collector.setOnFlush(flusher);

        final var serverTracingConfiguration = new ServerTracingConfiguration();
        serverTracingConfiguration.setOperation(tracingConfiguration.operation());
        serverTracingConfiguration.setServiceName(tracingConfiguration.service());
        serverTracingConfiguration.setTraceHeader(tracingConfiguration.traceHeader());
        serverTracingConfiguration.setSpanHeader(tracingConfiguration.spanHeader());
        serverTracingConfiguration.setTags(Map.of());

        if (tracingConfiguration.forceFlushTimeout() > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, ServerConfigurationCustomizer.class.getName() + "-scheduler"));
            scheduler.scheduleAtFixedRate(
                    collector::flush,
                    tracingConfiguration.forceFlushTimeout(),
                    tracingConfiguration.forceFlushTimeout(),
                    MILLISECONDS);
        }

        ctx.getPipeline()
                .addValve(new TracingValve(serverTracingConfiguration, collector, idGenerator, systemUTC(), true) {
                    @Override
                    public void invoke(final Request request, final Response response)
                            throws IOException, ServletException {
                        request.setAttribute(AccumulatingSpanCollector.class.getName(), collector);
                        super.invoke(request, response);
                    }

                    @Override
                    protected Span toSpan(
                            final HttpServletRequest request,
                            final Object traceId,
                            final String spanTrace,
                            final Object id,
                            final Instant start,
                            final long duration,
                            final Span.Endpoint localEndpoint,
                            final Map<String, Object> tags) {
                        addOperationTags(request, tags);
                        return super.toSpan(request, traceId, spanTrace, id, start, duration, localEndpoint, tags);
                    }
                });
    }

    // enable virtual threads for tomcat itself
    private void customizeTomcat(final Tomcat tomcat) {
        if (tomcat.getConnector().getProtocolHandler() instanceof AbstractProtocol<?> p) {
            p.setExecutor(Thread.ofVirtual().name("hcms-tomcat-", 0)::start);
        }
    }

    private void addOperationTags(final HttpServletRequest request, final Map<String, Object> tags) {
        ofNullable(request.getAttribute("yupiik.jsonrpc.method"))
                .ifPresent(jsonRpc -> tags.putIfAbsent("jsonrpc", jsonRpc.toString()));
    }
}
