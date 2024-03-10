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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

@ApplicationScoped
public class PasswordEncoder {
    private final PasswordConfiguration configuration;
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getEncoder();
    private final Base64.Decoder decoder = Base64.getDecoder();

    public PasswordEncoder(final PasswordConfiguration configuration) {
        this.configuration = configuration;
    }

    public String toDatabase(final String password) {
        final var salt = new byte[configuration.saltLength()];
        random.nextBytes(salt);
        try {
            final var spec =
                    new PBEKeySpec(password.toCharArray(), salt, configuration.iterations(), configuration.keyLength());
            final var key = encode(spec, configuration.algorithm());
            return String.join(
                    ";",
                    "1", // version
                    configuration.algorithm(),
                    Integer.toString(configuration.iterations()),
                    Integer.toString(configuration.keyLength()),
                    encoder.encodeToString(salt),
                    encoder.encodeToString(key));
        } catch (final NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean matches(final String clear, final String database) {
        if (!database.startsWith("1;")) { // not supported version
            return false;
        }

        try {
            final int algoEnd = database.indexOf(';', 2);
            if (algoEnd < 0) {
                return false;
            }
            final var algo = database.substring(2, algoEnd);

            final int iterationsEnd = database.indexOf(';', algoEnd + 1);
            if (iterationsEnd < 0) {
                return false;
            }
            final int iterations = Integer.parseInt(database.substring(algoEnd + 1, iterationsEnd));

            final int keyLengthEnd = database.indexOf(';', iterationsEnd + 1);
            if (keyLengthEnd < 0) {
                return false;
            }
            final int keyLength = Integer.parseInt(database.substring(iterationsEnd + 1, keyLengthEnd));

            final int saltEnd = database.indexOf(';', keyLengthEnd + 1);
            if (saltEnd < 0) {
                return false;
            }
            final var salt = decoder.decode(database.substring(keyLengthEnd + 1, saltEnd));
            final var hash = decoder.decode(database.substring(saltEnd + 1));

            return MessageDigest.isEqual(
                    hash, encode(new PBEKeySpec(clear.toCharArray(), salt, iterations, keyLength), algo));
        } catch (final RuntimeException | NoSuchAlgorithmException | InvalidKeySpecException re) {
            return false;
        }
    }

    private byte[] encode(final PBEKeySpec spec, final String algorithm)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        final var skf = SecretKeyFactory.getInstance(algorithm);
        return skf.generateSecret(spec).getEncoded();
    }
}
