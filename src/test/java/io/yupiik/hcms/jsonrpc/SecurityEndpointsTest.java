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
package io.yupiik.hcms.jsonrpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.hcms.test.HCMSSupport;
import io.yupiik.hcms.test.SimpleJsonRpcClient;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

@HCMSSupport
class SecurityEndpointsTest {
    @Test
    void refreshOk(@Fusion final SimpleJsonRpcClient client) {
        final var token = client.post(
                null,
                "hcms.security.login",
                Map.of(
                        "username", "test@app.com",
                        "password", "@dm1n"));
        final var refresh = client.post(
                null,
                "hcms.security.refresh",
                Map.of("token", token.as(Map.class).get("refresh_token"), "scope", "openid"));
        assertTrue(refresh.isOk());

        final var payload = refresh.as(Map.class);
        assertNotNull(payload.get("access_token"));
        assertNotNull(payload.get("refresh_token"));
    }

    @Test
    void refreshKo(@Fusion final SimpleJsonRpcClient client) {
        final var token = client.post(
                null,
                "hcms.security.login",
                Map.of(
                        "username", "test@app.com",
                        "password", "@dm1n"));
        final var refresh = client.post(
                null,
                "hcms.security.refresh",
                Map.of("token", token.as(Map.class).get("access_token") + "-", "scope", "openid"));
        assertFalse(refresh.isOk());
        assertEquals(BigDecimal.valueOf(401), refresh.as(Map.class).get("code"));
    }

    @Test
    void tokenOk(@Fusion final SimpleJsonRpcClient client) {
        final var response = client.post(
                null,
                "hcms.security.login",
                Map.of(
                        "username", "test@app.com",
                        "password", "@dm1n"));
        assertTrue(response.isOk());

        final var payload = response.as(Map.class);
        assertNotNull(payload.get("access_token"));
        assertNotNull(payload.get("refresh_token"));
    }

    @Test
    void tokenKoPassword(@Fusion final SimpleJsonRpcClient client) {
        final var response = client.post(
                null,
                "hcms.security.login",
                Map.of(
                        "username", "test@app.com",
                        "password", "adm1n"));
        assertFalse(response.isOk());
    }

    @Test
    void tokenKoUsername(@Fusion final SimpleJsonRpcClient client) {
        final var response = client.post(
                null,
                "hcms.security.login",
                Map.of(
                        "username", "wrong@app.com",
                        "password", "@dm1n"));
        assertFalse(response.isOk());
    }
}
