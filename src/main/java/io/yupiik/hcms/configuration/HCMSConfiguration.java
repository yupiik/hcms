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
package io.yupiik.hcms.configuration;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.jwt.JwtValidatorConfiguration;
import java.util.List;

@ApplicationScoped
@RootConfiguration("hcms")
public record HCMSConfiguration(
        @Property(
                        documentation = "List of disabled renderers (`findById`/`findAll` methods).",
                        defaultValue = "java.util.List.of()")
                List<String> disabledRenderers,
        @Property(documentation = "Database configuration.") DatabaseConfiguration database,
        @Property(value = "database-init", documentation = "Database initialization configuration.")
                DatabaseInitialization databaseInit,
        @Property(documentation = "Path of the model to deploy.", defaultValue = "\"conf/model.json\"")
                String modelLocation,
        @Property(
                        documentation = "Should dev mode be enabled, ie reload the model on read if it changed.",
                        defaultValue = "true")
                boolean devMode,
        @Property(
                        documentation =
                                "Max bulk size, i.e. how many requests can be sent at once to be executed in the same transaction.",
                        defaultValue = "50")
                int maxBulkRequest,
        @Property(documentation = "Security (JWT) configuration.") SecurityConfiguration security) {
    public record DatabaseConfiguration(
            @Property(documentation = "JDBC driver to use to get connections.") String driver,
            @Property(
                            documentation =
                                    "JDBC URL. Can be set to `-` for commands other than `serve` to ignore database provisioning/init.")
                    String url,
            @Property(documentation = "Database username.") String username,
            @Property(documentation = "Database password.") String password,
            @Property(
                            documentation = "Should connections be tested when retrieved from the pool.",
                            defaultValue = "false")
                    boolean testOnBorrow,
            @Property(
                            documentation = "Should connections be tested when returning to the pool.",
                            defaultValue = "false")
                    boolean testOnReturn,
            @Property(
                            documentation = "Should connections be tested when not used and in the pool.",
                            defaultValue = "true")
                    boolean testWhileIdle,
            @Property(documentation = "Timeout (ms) between connection tests.", defaultValue = "5000")
                    int timeBetweenEvictionRuns,
            @Property(documentation = "Timeout before any eviction test for a connection.", defaultValue = "60000")
                    int minEvictableIdleTime,
            @Property(documentation = "Test query for eviction - if not set the driver one is used.")
                    String validationQuery,
            @Property(documentation = "Default timeout for validations.", defaultValue = "30")
                    int validationQueryTimeout,
            @Property(documentation = "Min number of connection - even when nothing happens.", defaultValue = "2")
                    int minIdle,
            @Property(documentation = "Max active connections.", defaultValue = "100") int maxActive,
            @Property(documentation = "Should detected as abandonned connections be dropped.", defaultValue = "false")
                    boolean removeAbandoned,
            @Property(documentation = "Should autocommit be used.") Boolean defaultAutoCommit,
            @Property(documentation = "Should abandons be logged.") Boolean logAbandoned,
            @Property(documentation = "Abandon timeout.", defaultValue = "60") int removeAbandonedTimeout) {}

    public record SecurityConfiguration(
            @Property(
                            documentation =
                                    "RSA PEM (private key) - ensure to set the certificate in `keys` with the right `kid`.")
                    String privateKey,
            @Property(documentation = "JWT algorithm.", defaultValue = "\"RS256\"") String algorithm,
            @Property(documentation = "JWT issuer.", defaultValue = "\"https://hcms.yupiik.io/token/\"") String issuer,
            @Property(
                            documentation =
                                    "Active (signer) kid, must be found in `keys` to be able to validate it properly.",
                            defaultValue = "\"k0001\"")
                    String kid,
            @Property(documentation = "Tolerance for date validation (in seconds).", defaultValue = "30L")
                    long tolerance,
            @Property(documentation = "Access token validity for date validation (in seconds).", defaultValue = "900L")
                    long accessValidity,
            @Property(
                            documentation = "Refresh token validity for date validation (in seconds).",
                            defaultValue = "28_800L")
                    long refreshValidity,
            @Property(
                            documentation = "List of known keys (JWKS) for validations.",
                            defaultValue = "java.util.List.of()")
                    List<JwtValidatorConfiguration.JwkKey> keys) {}

    public record DatabaseInitialization(
            @Property(documentation = "Should database be initialized at startup.", defaultValue = "true")
                    boolean enabled,
            @Property(
                            documentation = "When `enabled` is `true`, should errors be ignored if any.",
                            defaultValue = "false")
                    boolean ignoreErrors,
            @Property(
                            documentation = "Classpath scripts to use to seed the database if create is `true`.",
                            defaultValue = "java.util.List.of(\"ddl/01-create-database.h2.sql\")")
                    List<String> scripts) {}
}
