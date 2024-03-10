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
package io.yupiik.hcms.service.security;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.fusion.http.server.api.Body;
import io.yupiik.fusion.http.server.api.Cookie;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.hcms.service.model.json.Model;
import io.yupiik.hcms.test.HCMSSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

@HCMSSupport
class SecurityHandlerTest {
    @Test
    void anonymous(@Fusion final JwtService service, @Fusion final SecurityHandler securityHandler)
            throws ExecutionException, InterruptedException {
        final var accessToken = service.forgeAccessToken(Map.of("sub", "hcms"));

        // auth with no conf
        assertEquals("ok", exec(securityHandler, null, accessToken));

        // auth with anonymous access
        assertEquals("ok", exec(securityHandler, new Model.SecurityValidation(true, false, null), accessToken));

        // no auth with conf
        assertEquals("ok", exec(securityHandler, new Model.SecurityValidation(true, false, null), null));

        // no auth, no conf
        assertEquals("ok", exec(securityHandler, null, null));
    }

    @Test
    void logged(@Fusion final JwtService service, @Fusion final SecurityHandler securityHandler)
            throws ExecutionException, InterruptedException {
        final var accessToken = service.forgeAccessToken(Map.of("sub", "hcms"));
        final var logged = new Model.SecurityValidation(false, true, null);

        // auth with logged access
        assertEquals("ok", exec(securityHandler, logged, accessToken));

        // wrong auth with conf
        assertThrows(JsonRpcException.class, () -> exec(securityHandler, logged, accessToken + "_wrong"));

        // no auth
        assertThrows(JsonRpcException.class, () -> exec(securityHandler, logged, null));
    }

    @Test
    void roles(@Fusion final JwtService service, @Fusion final SecurityHandler securityHandler)
            throws ExecutionException, InterruptedException {
        final var accessTokenOk =
                service.forgeAccessToken(Map.of("sub", "hcms", "roles", List.of("user:hcms", "test")));
        final var accessTokenKo = service.forgeAccessToken(Map.of("sub", "hcms", "roles", List.of("user:hcms")));
        final var expectedRole = new Model.SecurityValidation(false, false /* implicitly true */, List.of("test"));

        assertEquals("ok", exec(securityHandler, expectedRole, accessTokenOk));
        assertThrows(JsonRpcException.class, () -> exec(securityHandler, expectedRole, accessTokenKo));
        assertThrows(JsonRpcException.class, () -> exec(securityHandler, expectedRole, null));
    }

    private Object exec(
            final SecurityHandler securityHandler,
            final Model.SecurityValidation securityValidation,
            final String accessToken)
            throws ExecutionException, InterruptedException {
        return securityHandler
                .compile(securityValidation, cx -> completedFuture("ok"))
                .apply(new JsonRpcMethod.Context(request(accessToken), Map.of()))
                .toCompletableFuture()
                .get();
    }

    private Request request(final String accessToken) {
        return new Request() {
            @Override
            public String header(final String name) {
                return "authorization".equalsIgnoreCase(name) && accessToken != null ? "Bearer " + accessToken : null;
            }

            @Override
            public String scheme() {
                return null;
            }

            @Override
            public String method() {
                return null;
            }

            @Override
            public String path() {
                return null;
            }

            @Override
            public String query() {
                return null;
            }

            @Override
            public Body fullBody() {
                return null;
            }

            @Override
            public Stream<Cookie> cookies() {
                return null;
            }

            @Override
            public String parameter(final String name) {
                return null;
            }

            @Override
            public Map<String, String[]> parameters() {
                return null;
            }

            @Override
            public Map<String, List<String>> headers() {
                return null;
            }

            @Override
            public <T> T attribute(final String key, final Class<T> type) {
                return null;
            }

            @Override
            public <T> void setAttribute(final String key, final T value) {}
        };
    }
}
