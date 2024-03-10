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
package io.yupiik.hcms.jsonrpc.extension;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.logging.Level.SEVERE;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcHandler;
import io.yupiik.fusion.jsonrpc.JsonRpcRegistry;
import io.yupiik.fusion.jsonrpc.Response;
import io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod;
import io.yupiik.fusion.persistence.api.TransactionManager;
import io.yupiik.hcms.configuration.HCMSConfiguration;
import io.yupiik.hcms.service.model.ModelHandler;
import io.yupiik.hcms.service.tracing.ClientSpanService;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

@DefaultScoped
public class ExtendedJsonRpcHandler extends JsonRpcHandler {
    private static final String CONNECTION_ATTR = ExtendedJsonRpcHandler.class.getName() + ".inheritTx";

    private final Logger logger = Logger.getLogger(getClass().getName());

    private final HCMSConfiguration configuration;
    private final TransactionManager tx;
    private final ModelHandler modelHandler;
    private final ClientSpanService spans;

    public ExtendedJsonRpcHandler(
            final HCMSConfiguration configuration,
            final RuntimeContainer emitter,
            final JsonMapper mapper,
            final JsonRpcRegistry registry,
            final TransactionManager tx,
            final ModelHandler modelHandler,
            final ClientSpanService spans) {
        super(emitter, mapper, registry);
        this.configuration = configuration;
        this.tx = tx;
        this.modelHandler = modelHandler;
        this.spans = spans;
    }

    @Override
    protected int getMaxBulkRequests() {
        return configuration.maxBulkRequest();
    }

    @Override
    protected CompletableFuture<List<Response>> handleRequests(
            final List<Tuple2<Map<String, Object>, Object>> requests, final Request httpRequest) {
        if (requests.stream().map(Tuple2::first).anyMatch(Objects::isNull)) {
            return super.handleRequests(requests, httpRequest); // will fail anyway so don't do anything
        }

        final var methods = requests.stream()
                .map(Tuple2::first)
                .map(r -> r.getOrDefault("method", "").toString())
                .collect(toSet());

        // at least one method is not a virtual method, we can't optimize it
        if (methods.stream().anyMatch(it -> it.startsWith("hcms") || !isVirtual(it))) {
            return super.handleRequests(requests, httpRequest);
        }

        // only virtual findByIds so push down on the database the optimization
        if (requests.stream()
                        .filter(it -> {
                            final var method =
                                    it.first().getOrDefault("method", "").toString();
                            return method.endsWith(".findById")
                                    && modelHandler.hasEntity(
                                            method.substring(0, method.length() - ".findById".length()));
                        })
                        .count()
                == requests.size()) {
            return spans.wrap(
                    httpRequest,
                    "jsonrpc.bulkFindByIds",
                    Map.of("size", Integer.toString(requests.size())),
                    () -> bulkFindByIds(requests, httpRequest));
        }

        // we have only virtual method so use a single transaction
        return methods.stream().anyMatch(this::isWriteMethod)
                ? tx.writeSQL(c -> {
                    httpRequest.setAttribute(CONNECTION_ATTR, c);
                    return super.handleRequests(requests, httpRequest);
                })
                : tx.readSQL(c -> {
                    httpRequest.setAttribute(CONNECTION_ATTR, c);
                    return super.handleRequests(requests, httpRequest);
                });
    }

    private boolean isVirtual(final String method) {
        final int lastDot = method.lastIndexOf('.');
        return lastDot > 0 && modelHandler.hasEntity(method.substring(0, lastDot));
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<List<Response>> bulkFindByIds(
            final List<Tuple2<Map<String, Object>, Object>> requests, final Request httpRequest) {
        final var perEntity = requests.stream()
                .collect(groupingBy(
                        it -> {
                            final var name = it.first().get("method").toString();
                            final var entity = name.substring(0, name.length() - ".findById".length());
                            if (it.second() instanceof Map<?, ?> req && req.get("params") instanceof Map<?, ?> params) {
                                return new FindById(
                                        entity,
                                        params.get("fields") instanceof List<?> f ? (List<String>) f : null,
                                        params.get("renderers") instanceof Map<?, ?> r
                                                ? (Map<String, String>) r
                                                : null);
                            }
                            return new FindById(entity, null, null);
                        },
                        mapping(Tuple2::first, toList())));
        try {
            final var results = tx.readSQL(c -> perEntity.entrySet().stream()
                    .flatMap(e -> modelHandler
                            .findByIds(
                                    httpRequest,
                                    c,
                                    e.getKey().entity(),
                                    e.getValue(),
                                    new JsonRpcMethod.Context(httpRequest, Map.of()),
                                    modelHandler.toRenderers(e.getKey().renderers()),
                                    e.getKey().fields())
                            .entrySet()
                            .stream())
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)));
            httpRequest.setAttribute(
                    "yupiik.jsonrpc.method",
                    perEntity.keySet().stream().map(m -> m + ".findById").collect(joining(",")));

            return completedFuture(requests.stream()
                    .map(Tuple2::first)
                    .map(r -> {
                        final var id = r.get("id") instanceof String s ? s : null;
                        final var result = results.get(r);
                        return new Response("2.0", id, result, null);
                    })
                    .toList());
        } catch (final RuntimeException re) {
            logger.log(SEVERE, re, re::getMessage);
            return completedFuture(requests.stream()
                    .map(r -> createResponse(
                            r.first(), 500, "Can't execute the action, cancelling the full bulk request."))
                    .toList());
        }
    }

    private boolean isWriteMethod(final String method) {
        return method.endsWith(".create") || method.endsWith(".update");
    }

    @DefaultScoped
    public static class Registration {
        @Bean
        @Order(2_000)
        public JsonRpcHandler overrideDefaultHandler(final ExtendedJsonRpcHandler handler) {
            return handler;
        }
    }

    public static Connection findConnection(final Request request) {
        return request.attribute(CONNECTION_ATTR, Connection.class);
    }

    private record FindById(String entity, List<String> fields, Map<String, String> renderers) {}
}
