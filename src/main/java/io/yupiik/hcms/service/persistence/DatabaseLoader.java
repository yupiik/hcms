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

import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.SEVERE;
import static java.util.stream.Collectors.joining;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.persistence.api.TransactionManager;
import io.yupiik.hcms.configuration.HCMSConfiguration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

@ApplicationScoped
public class DatabaseLoader {
    private final TransactionManager transactionManager;
    private final HCMSConfiguration configuration;

    public DatabaseLoader(final TransactionManager transactionManager, final HCMSConfiguration configuration) {
        this.transactionManager = transactionManager;
        this.configuration = configuration;
    }

    public void execute(final List<String> scripts, final boolean ignoreErrors) {
        if (configuration.database().url() == null
                || configuration.database().url().isBlank()
                || "-".equals(configuration.database().url())) {
            if (!scripts.isEmpty()) {
                Logger.getLogger(getClass().getName()).warning(() -> "No database url, ignoring script provisioning");
            }
            return;
        }

        final var content = new StringBuilder();
        for (final var sql : scripts) {
            try (final var reader =
                    new BufferedReader(new InputStreamReader(requireNonNull(findStream(sql), "no '" + sql + "'")))) {
                content.append(reader.lines()
                        .filter(it -> !it.isBlank() && !it.startsWith("--"))
                        .collect(joining("\n", "", "\n")));
            } catch (final IOException e) { // will auto rollback (see withConnection)
                throw new IllegalStateException(e);
            }
        }
        transactionManager.writeSQL(c -> {
            for (final var sql : content.toString().split("\n")) {
                if (sql.isBlank() || sql.startsWith("--")) {
                    continue;
                }
                try (final var stmt = c.prepareStatement(sql.strip())) {
                    stmt.execute();
                } catch (final SQLException sqle) {
                    if (ignoreErrors) {
                        Logger.getLogger(getClass().getName()).log(SEVERE, sqle, sqle::getMessage);
                        continue;
                    }
                    throw sqle;
                }
            }
            if (!c.getAutoCommit()) {
                c.commit();
            }
            return null;
        });
    }

    private InputStream findStream(final String sql) throws IOException {
        final var path = Path.of(sql);
        if (Files.exists(path)) {
            return Files.newInputStream(path);
        }

        final var loader = Thread.currentThread().getContextClassLoader();
        {
            final var stream = loader.getResourceAsStream(sql);
            if (stream != null) {
                return stream;
            }
        }

        // relative to model
        final var model = Path.of(configuration.modelLocation());
        if (Files.exists(model)) {
            final var parent = model.getParent();
            if (parent != null) {
                final var relative = parent.resolve(sql);
                if (Files.exists(relative)) {
                    return Files.newInputStream(relative);
                }
            }
        }

        if (configuration.modelLocation().contains("/")) {
            final var res = configuration
                            .modelLocation()
                            .substring(0, configuration.modelLocation().lastIndexOf('/'))
                    + '/'
                    + sql;
            final var stream = loader.getResourceAsStream(res);
            if (stream != null) {
                return stream;
            }
        }

        return null;
    }
}
