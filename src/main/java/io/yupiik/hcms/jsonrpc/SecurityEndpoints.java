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

import static java.util.logging.Level.SEVERE;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpc;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpcError;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpcParam;
import io.yupiik.fusion.http.server.api.HttpException;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.jsonrpc.api.PartialResponse;
import io.yupiik.fusion.jwt.Jwt;
import io.yupiik.fusion.persistence.api.TransactionManager;
import io.yupiik.hcms.configuration.HCMSConfiguration;
import io.yupiik.hcms.jsonrpc.model.ErrorMessage;
import io.yupiik.hcms.jsonrpc.model.Token;
import io.yupiik.hcms.service.persistence.UserRepository;
import io.yupiik.hcms.service.persistence.entity.User;
import io.yupiik.hcms.service.security.JwtService;
import io.yupiik.hcms.service.security.PasswordEncoder;
import io.yupiik.hcms.service.tracing.ClientSpanService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Stream;

@ApplicationScoped
public class SecurityEndpoints {
    private final Logger logger = Logger.getLogger(getClass().getName());

    private final HCMSConfiguration configuration;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TransactionManager transactionManager;
    private final JwtService jwtService;
    private final ClientSpanService spans;

    public SecurityEndpoints(
            final HCMSConfiguration configuration,
            final PasswordEncoder passwordEncoder,
            final UserRepository users,
            final TransactionManager transactionManager,
            final JwtService jwtService,
            final ClientSpanService spans) {
        this.configuration = configuration;
        this.users = users;
        this.jwtService = jwtService;
        this.transactionManager = transactionManager;
        this.passwordEncoder = passwordEncoder;
        this.spans = spans;
    }

    @JsonRpc(
            value = "hcms.security.refresh",
            documentation = "Refreshes a token.",
            errors = {
                @JsonRpcError(code = 400, documentation = "Invalid token."),
                @JsonRpcError(code = 401, documentation = "Invalid authentication.")
            })
    public PartialResponse<Token> refresh(
            @JsonRpcParam(required = true, documentation = "Refresh token.") final String token,
            final Request request) {
        final Jwt jwt;
        try {
            jwt = spans.wrap(request, "security.verifyJwt", Map.of(), () -> jwtService.verify(token));
        } catch (final RuntimeException re) {
            throw new JsonRpcException(401, "Invalid JWT.", null, re);
        }
        try {
            if (!"refresh".equals(jwt.claim("type", String.class).orElse(""))) {
                throw new JsonRpcException(400, "Invalid token type.");
            }

            final var sub = jwt.claim("sub", String.class)
                    .orElseThrow(() -> new JsonRpcException(
                            401, "invalid_token", new ErrorMessage("invalid_token", "Invalid token."), null));
            return newToken(request, findUser(request, sub, u -> true));
        } catch (final JsonRpcException | HttpException e) {
            throw e;
        } catch (final RuntimeException re) {
            throw new JsonRpcException(
                    401,
                    "invalid_token: " + re.getMessage(),
                    new ErrorMessage("invalid_credentials", "Invalid token."),
                    re);
        }
    }

    @JsonRpc(
            value = "hcms.security.login",
            documentation = "Logs in creating an access token.",
            errors = @JsonRpcError(code = 400, documentation = "Invalid credentials."))
    public PartialResponse<Token> login(
            @JsonRpcParam(required = true, documentation = "Username.") final String username,
            @JsonRpcParam(required = true, documentation = "Password.") final String password,
            final Request request) {
        return newToken(
                request,
                findUser(
                        request,
                        username,
                        u -> spans.wrap(
                                request,
                                "security.passwordMatches",
                                Map.of(),
                                () -> passwordEncoder.matches(password, u.passwordHash()))));
    }

    private List<String> toRoles(final User user, final List<String> roles) {
        return Stream.concat(
                        Stream.of("user:" + user.id()), // implicit self role
                        roles.stream())
                .toList();
    }

    private PartialResponse<Token> newToken(final Request request, final UserRoles user) {
        return spans.wrap(request, "security.newToken", Map.of("username", user.user()), () -> {
            final var accessToken = jwtService.forgeAccessToken(Map.of(
                    "type", "access",
                    "sub", user.user().login(),
                    "roles", user.roles()));
            final var refreshToken = jwtService.forgeRefreshToken(
                    Map.of("type", "refresh", "sub", user.user().login()));
            final var token = new Token(
                    "Bearer",
                    accessToken,
                    refreshToken,
                    TimeUnit.MILLISECONDS.toSeconds(configuration.security().accessValidity()));
            return new PartialResponse<>(token)
                    .setHttpResponseHeaders(Map.of(
                            "Pragma", "no-cache",
                            "Cache-Control", "no-store",
                            "Content-Type", "application/json"));
        });
    }

    private UserRoles findUser(final Request request, final String username, final Predicate<User> validator) {
        try {
            return transactionManager.readSQL(c -> {
                final var user = users.findByLogin(request, c, username)
                        .filter(validator)
                        .orElseThrow(this::invalidCredentials);
                final var roles = users.findRoleByUserId(request, c, user.id());
                return new UserRoles(user, toRoles(user, roles));
            });
        } catch (final RuntimeException iae) {
            logger.log(SEVERE, iae, () -> "Can't load user '" + username + "': " + iae.getMessage());
            throw invalidCredentials();
        }
    }

    private JsonRpcException invalidCredentials() {
        return new JsonRpcException(
                401,
                "Invalid username/password.",
                new ErrorMessage("invalid_credentials", "Invalid username/password."),
                null);
    }

    private record UserRoles(User user, List<String> roles) {}
}
