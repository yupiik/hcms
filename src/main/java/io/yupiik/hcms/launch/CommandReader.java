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
package io.yupiik.hcms.launch;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.main.Args;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

@ApplicationScoped
public class CommandReader {
    private final String command;

    public CommandReader(final Configuration configuration, final Optional<Args> args) {
        this.command = configuration == null
                ? null
                : configuration
                        .get("hcms.command")
                        .or(() -> args.map(Args::args)
                                .filter(Predicate.not(List::isEmpty))
                                .map(a -> {
                                    final var first = a.get(0);
                                    return "--hcms-command".equals(first) ? a.size() >= 2 ? a.get(1) : first : first;
                                }))
                        .filter(it -> !it.startsWith("--")) // assume an option
                        .orElse("serve");
    }

    public String command() {
        return command;
    }
}
