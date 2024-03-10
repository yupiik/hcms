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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.hcms.test.HCMSSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@HCMSSupport
@TestInstance(PER_CLASS)
class PasswordEncoderTest {
    @Test
    void roundTrip(@Fusion final PasswordEncoder encoder) {
        final var encoded = encoder.toDatabase("secret");
        assertNotNull(encoded);
        assertFalse(encoded.isBlank());
        assertTrue(encoder.matches("secret", encoded));
        assertFalse(encoder.matches("secRet", encoded));
    }
}
