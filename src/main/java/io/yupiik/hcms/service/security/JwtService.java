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

import static java.util.logging.Level.SEVERE;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.jwt.Jwt;
import io.yupiik.fusion.jwt.JwtSignerConfiguration;
import io.yupiik.fusion.jwt.JwtSignerFactory;
import io.yupiik.fusion.jwt.JwtValidatorConfiguration;
import io.yupiik.fusion.jwt.JwtValidatorFactory;
import io.yupiik.hcms.configuration.HCMSConfiguration;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

@ApplicationScoped
public class JwtService {
    private final Logger logger = Logger.getLogger(getClass().getName());

    private final Function<String, Jwt> validator;
    private final Function<Map<String, Object>, String> accessSigner;
    private final Function<Map<String, Object>, String> refreshSigner;

    protected JwtService() {
        this.validator = null;
        this.accessSigner = null;
        this.refreshSigner = null;
    }

    public JwtService(
            final HCMSConfiguration configuration,
            final JwtSignerFactory signerFactory,
            final JwtValidatorFactory validatorFactory) {
        final var conf = configuration.security();
        var privateKey = conf.privateKey();
        var keys = conf.keys();
        if (privateKey == null) {
            logger.warning(
                    () ->
                            "No private key configured, one will be generated for this instance but ensure to configure one for stability on multi-instance deployments");
            try {
                final var key = KeyPairGenerator.getInstance("RSA");
                key.initialize(4_096);
                final var keyPair = key.generateKeyPair();
                privateKey =
                        Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
                if (keys == null || keys.isEmpty()) {
                    keys = List.of(new JwtValidatorConfiguration.JwkKey(
                            "default",
                            "RSA",
                            "RS256",
                            "sig",
                            null,
                            null,
                            List.of(Base64.getEncoder()
                                    .encodeToString(keyPair.getPublic().getEncoded())),
                            null,
                            null,
                            null));
                }
            } catch (final NoSuchAlgorithmException e) {
                throw new IllegalStateException("can't generate a JWT key, please configure it: " + e.getMessage());
            }
        }

        this.accessSigner = signerFactory.newJwtFactory(new JwtSignerConfiguration(
                privateKey, conf.algorithm(), conf.issuer(), conf.kid(), true, conf.accessValidity(), true, true));
        this.validator = validatorFactory.newValidator(new JwtValidatorConfiguration(
                "",
                "", // ignore default key, just rely on conf.keys()
                conf.issuer(),
                30L,
                true,
                true,
                true,
                true,
                keys));
        this.refreshSigner = conf.refreshValidity() == conf.accessValidity()
                ? accessSigner
                : signerFactory.newJwtFactory(new JwtSignerConfiguration(
                        conf.privateKey(),
                        conf.algorithm(),
                        conf.issuer(),
                        conf.kid(),
                        true,
                        conf.refreshValidity(),
                        true,
                        true));
    }

    public String forgeAccessToken(final Map<String, Object> data) {
        return accessSigner.apply(data);
    }

    public String forgeRefreshToken(final Map<String, Object> data) {
        return refreshSigner.apply(data);
    }

    public Jwt verify(final String jwt) {
        try {
            return validator.apply(jwt);
        } catch (final RuntimeException re) {
            logger.log(SEVERE, re, () -> "Can't verify the JWT '" + jwt + "': " + re.getMessage());
            throw new JsonRpcException(401, "unauthenticated_request");
        }
    }
}
