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
package io.yupiik.hcms.test;

import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.fail;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.http.server.api.WebServer;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

@ApplicationScoped
public class SimpleJsonRpcClient {
    private final WebServer webServer;
    private final JsonMapper jsonMapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public SimpleJsonRpcClient(final WebServer webServer, final JsonMapper jsonMapper) {
        this.webServer = webServer;
        this.jsonMapper = jsonMapper;
    }

    @Destroy
    public void destroy() {
        client.close();
    }

    public HttpClient client() {
        return client;
    }

    public URI endpoint() {
        return URI.create("http://localhost:" + webServer.configuration().port())
                .resolve("/jsonrpc");
    }

    public JsonRpcResponse post(final String jwt, final String method, final Map<String, Object> params) {
        try {
            final var baseRequest = HttpRequest.newBuilder()
                    .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.toString(Map.of(
                            "jsonrpc", "2.0",
                            "method", method,
                            "params", params))))
                    .uri(endpoint())
                    .header("content-type", "application/json");
            final var response = client.send(
                    (jwt != null ? baseRequest.headers("authorization", "Bearer " + jwt) : baseRequest).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new JsonRpcResponse(response, jsonMapper);
        } catch (final IOException e) {
            return fail(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(e);
        }
    }

    public static class JsonRpcResponse {
        private final HttpResponse<String> response;
        private final Map<String, Object> body;
        private final JsonMapper mapper;

        @SuppressWarnings("unchecked")
        public JsonRpcResponse(final HttpResponse<String> response, final JsonMapper jsonMapper) {
            this.response = response;
            this.mapper = jsonMapper;
            this.body = response.body() != null
                    ? (Map<String, Object>) jsonMapper.fromString(Object.class, response.body())
                    : null;
        }

        public boolean isOk() {
            return response.statusCode() == 200 && !body.containsKey("error");
        }

        @SuppressWarnings("unchecked")
        public <T> T as(final Class<T> type) {
            if (type == String.class) {
                return (T) response.body();
            }
            return mapper.fromString(
                    type, mapper.toString(ofNullable(body.get("result")).orElseGet(() -> body.get("error"))));
        }

        public String debug() {
            return response + "\n" + response.body();
        }
    }
}
