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
import io.yupiik.fusion.framework.build.api.lifecycle.Init;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod;
import io.yupiik.fusion.jwt.Jwt;
import io.yupiik.hcms.service.model.json.Model;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

@ApplicationScoped
public class SecurityHandler {
    private final JsonRpcException unauthenticated = new JsonRpcException(401, "Missing authorization header");
    private final JwtService jwts;

    public SecurityHandler(final JwtService jwts) {
        this.jwts = jwts;
    }

    @Init
    protected void init() {
        unauthenticated.setStackTrace(new StackTraceElement[0]);
    }

    public Function<JsonRpcMethod.Context, CompletionStage<?>> compile(
            final Model.SecurityValidation spec, final Function<JsonRpcMethod.Context, CompletionStage<?>> impl) {
        if (spec == null || spec.anonymous()) {
            return ctx -> {
                try { // force jwt to be loaded if needed by implicit clauses
                    loadJwt(ctx);
                } catch (final JsonRpcException e) {
                    if (e != unauthenticated) { // if JWT is missing it is ok for anonymous calls
                        throw e;
                    }
                }
                return impl.apply(ctx);
            };
        }

        final var requiredRoles = spec.roles();

        if (spec.logged() && (requiredRoles == null || requiredRoles.isEmpty())) {
            return ctx -> {
                loadJwt(ctx);
                return impl.apply(ctx);
            };
        }

        // implicit logged for role requirement
        return ctx -> {
            @SuppressWarnings("unchecked")
            final var jwtRoles = loadJwt(ctx)
                    .claim("roles", Object.class)
                    .filter(roles -> roles instanceof List<?>)
                    .map(roles -> {
                        try {
                            return (List<String>) roles;
                        } catch (final ClassCastException cce) {
                            return List.of();
                        }
                    })
                    .orElseThrow(() -> new JsonRpcException(403, "Invalid authorization header"));
            if (requiredRoles.stream().noneMatch(jwtRoles::contains)) {
                throw new JsonRpcException(403, "Invalid authorization header");
            }
            return impl.apply(ctx);
        };
    }

    private Jwt loadJwt(final JsonRpcMethod.Context ctx) {
        final var alreadyValidated = ctx.request().attribute(Jwt.class.getName(), Jwt.class);
        if (alreadyValidated != null) { // don't do crypto N times!
            return alreadyValidated;
        }

        final var authorization = ctx.request().header("authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw unauthenticated;
        }

        final var jwt = jwts.verify(authorization.substring("Bearer ".length()));
        ctx.request().setAttribute(Jwt.class.getName(), jwt);
        return jwt;
    }
}
