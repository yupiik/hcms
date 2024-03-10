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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.hcms.test.HCMSSupport;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@HCMSSupport
@TestInstance(PER_CLASS)
class JwtServiceTest {
    @Test
    void validRoundTrip(@Fusion final JwtService service) {
        final var accessToken = service.forgeAccessToken(Map.of("sub", "hcms"));
        final var jwt = service.verify(accessToken);
        assertEquals("hcms", jwt.claim("sub", String.class).orElseThrow());
        assertNotNull(jwt.claim("jti", String.class));
    }

    @Test
    void failOnInvalid(@Fusion final JwtService service) {
        final var valid = service.forgeAccessToken(Map.of("sub", "hcms"));

        // this is just not even decodable
        assertThrows(JsonRpcException.class, () -> service.verify('a' + valid));

        // reforge with a different header so signature is invalid
        assertThrows(
                JsonRpcException.class,
                () -> service.verify(Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString("{\"kid\":\"k001\",\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(UTF_8))
                        + valid.substring(valid.indexOf('.'))));

        // expired
        assertThrows(
                JsonRpcException.class,
                () -> service.verify(service.forgeAccessToken(Map.of("sub", "hcms", "exp", "0"))));
    }
}
