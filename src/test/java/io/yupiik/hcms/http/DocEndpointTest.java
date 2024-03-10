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
package io.yupiik.hcms.http;

import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.hcms.test.HCMSSupport;
import io.yupiik.hcms.test.SimpleJsonRpcClient;
import java.io.IOException;
import java.net.http.HttpRequest;
import org.junit.jupiter.api.Test;

@HCMSSupport
class DocEndpointTest {
    @Test
    void openrpc(@Fusion final SimpleJsonRpcClient client) throws IOException, InterruptedException {
        final var response = client.client()
                .send(
                        HttpRequest.newBuilder()
                                .GET()
                                .uri(client.endpoint().resolve("/openrpc.json"))
                                .build(),
                        ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"openrpc\":\"1.2.1\""), response::body);
        // todo: enhance checks
    }

    @Test
    void openapi(@Fusion final SimpleJsonRpcClient client) throws IOException, InterruptedException {
        final var response = client.client()
                .send(
                        HttpRequest.newBuilder()
                                .GET()
                                .uri(client.endpoint().resolve("/openapi.json"))
                                .build(),
                        ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"openapi\":\"3.0.3\""), response::body);
        assertTrue(response.body().contains("\"id\":{\"type\":\"string\"}"), response::body);
    }
}
