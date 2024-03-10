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

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

@RootConfiguration("hcms.password")
public record PasswordConfiguration(
        @Property(documentation = "Number of iterations to use for password hashing.", defaultValue = "310_000")
                int iterations,
        @Property(value = "key-length", documentation = "Key length to use for password hashing.", defaultValue = "512")
                int keyLength,
        @Property(
                        value = "salt-length",
                        documentation = "Salt length to use for password hashing.",
                        defaultValue = "16")
                int saltLength,
        @Property(documentation = "Algorithm to use for password hashing.", defaultValue = "\"PBKDF2WithHmacSHA256\"")
                String algorithm) {}
