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
package io.yupiik.hcms.service.tracing;

import static java.time.Clock.systemUTC;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.tracing.collector.AccumulatingSpanCollector;
import io.yupiik.fusion.tracing.id.IdGenerator;
import io.yupiik.fusion.tracing.request.PendingSpan;
import io.yupiik.fusion.tracing.span.Span;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.tomcat.jdbc.pool.DataSource;

@ApplicationScoped
public class ClientSpanService {
    private final Clock clock = systemUTC();

    private final IdGenerator idGenerator;
    private final Span.Endpoint remoteEndpoint;

    public ClientSpanService(final IdGenerator idGenerator, final DataSource dataSource) {
        this.idGenerator = idGenerator;
        this.remoteEndpoint = dataSource == null ? null : toEndpoint(dataSource.getUrl());
    }

    public <T> T wrap(
            final Request request, final String name, final Map<String, Object> customTags, final Supplier<T> task) {
        final var parent = request.attribute(PendingSpan.class.getName(), PendingSpan.class);
        if (parent == null) {
            return task.get();
        }

        final var collector =
                request.attribute(AccumulatingSpanCollector.class.getName(), AccumulatingSpanCollector.class);
        if (collector == null) {
            return task.get();
        }

        final var tags = new HashMap<>(customTags);
        tags.put("component", "jdbc");
        tags.put("source", "connection");
        tags.put("db.type", "sql");
        tags.put("span.kind", "CLIENT");

        final var start = clock.instant();
        try {
            return task.get();
        } catch (final RuntimeException | Error re) {
            if (re.getCause() instanceof SQLException se) {
                tags.put("error", Integer.toString(se.getErrorCode()));
                tags.put("error_type", "SQL");
            } else {
                tags.put("error", re.getMessage());
                tags.put("error_type", re.getClass().getSimpleName());
            }
            throw re;
        } finally {
            final var end = clock.instant();
            collector.accept(new Span(
                    parent.traceId(),
                    parent.id(),
                    idGenerator.get(),
                    name,
                    "CLIENT",
                    TimeUnit.MILLISECONDS.toMicros(start.toEpochMilli()),
                    TimeUnit.MILLISECONDS.toMicros(Duration.between(start, end).toMillis()),
                    null,
                    remoteEndpoint,
                    tags,
                    List.of(),
                    false,
                    false));
        }
    }

    private Span.Endpoint toEndpoint(final String url) {
        if (url.startsWith("jdbc:postgresql://")) {
            final var hostStart = "jdbc:postgresql://".length() + 1;
            final int end = url.indexOf('/', hostStart);
            if (end < 0) {
                return new Span.Endpoint("hcms", null, null, -1);
            }

            final int portStart = url.indexOf(':', hostStart);

            final int port = portStart > 0 ? Integer.parseInt(url.substring(portStart + 1)) : -1;

            final var host = url.substring(hostStart, portStart > 0 ? portStart : end);
            if (host.contains("::")) { // assume ipv6
                return new Span.Endpoint("hcms", null, host, port);
            }

            if (host.split("\\.").length == 4) {
                return new Span.Endpoint("hcms", host, null, port);
            }

            try {
                final var hostAddress = InetAddress.getByName(host);
                if (hostAddress instanceof Inet6Address) {
                    return new Span.Endpoint("hcms", null, hostAddress.getHostAddress(), port);
                }

                return new Span.Endpoint("hcms", hostAddress.getHostAddress(), null, port);
            } catch (final UnknownHostException e) {
                throw new IllegalArgumentException("can't find ip from '" + host + "': " + e.getMessage(), e);
            }
        }
        return new Span.Endpoint("hcms", null, null, -1);
    }
}
