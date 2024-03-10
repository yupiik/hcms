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
package io.yupiik.hcms.service.persistence;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.persistence.api.ContextLessDatabase;
import io.yupiik.hcms.service.persistence.entity.User;
import io.yupiik.hcms.service.tracing.ClientSpanService;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

@ApplicationScoped
public class UserRepository {
    private final Logger logger = Logger.getLogger(getClass().getName());

    private final ContextLessDatabase database;

    private final String findByUsername;
    private final String findRolesByUsername;
    private final ClientSpanService spans;

    protected UserRepository() {
        this.database = null;
        this.spans = null;
        this.findByUsername = null;
        this.findRolesByUsername = null;
    }

    public UserRepository(final ContextLessDatabase database, final ClientSpanService spans) {
        this.database = database;
        this.spans = spans;
        this.findByUsername = database.entity(User.class).getFindAllQuery()
                + " WHERE lower(login) = lower(?) AND ENABLED = TRUE" + " OFFSET 0 ROWS FETCH NEXT 2 ROWS ONLY";
        this.findRolesByUsername =
                "SELECT DISTINCT name FROM HCMS_ROLE WHERE ID in (SELECT ID FROM HCMS_USER_ROLE WHERE USER_ID = ?)";
    }

    public Optional<User> findByLogin(final Request request, final Connection connection, final String login) {
        final var matched = spans.wrap(
                request,
                "users.findByLogin",
                Map.of("sql", findByUsername),
                () -> database.query(connection, User.class, findByUsername, b -> b.bind(login)));
        if (matched.size() > 1) {
            logger.severe("Ambiguous user: '" + login + "'");
            return Optional.empty();
        }
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matched.get(0));
    }

    public List<String> findRoleByUserId(final Request request, final Connection connection, final String userId) {
        return spans.wrap(
                request,
                "users.findRoleByUserId",
                Map.of("sql", findRolesByUsername),
                () -> database.query(
                        connection, findRolesByUsername, b -> b.bind(userId), r -> r.mapAll(s -> s.getString(1))));
    }
}
