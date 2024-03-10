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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SimpleJwts {
    private final Map<String, String> jwts = new ConcurrentHashMap<>();
    private final SimpleJsonRpcClient client;

    public SimpleJwts(final SimpleJsonRpcClient client) {
        this.client = client;
    }

    public String forUser(final String name) {
        return jwts.computeIfAbsent(
                name, t -> client.post(null, "hcms.security.login", Map.of("username", name, "password", "@dm1n"))
                        .as(Map.class)
                        .get("access_token")
                        .toString());
    }
}
