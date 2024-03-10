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
package io.yupiik.hcms.server;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import io.yupiik.fusion.http.server.api.WebServer;
import io.yupiik.hcms.launch.CommandReader;

/**
 * Override default server for commands to avoid to start Tomcat.
 * <p>
 * Ex: {@code hcms --hcms-command generate-frisby ....}.
 * <p>
 * It works thanks to {@link io.yupiik.hcms.launch.SelectAwaiter}.
 */
@DefaultScoped
public class ServerBean {
    @Bean
    @Order(2_000)
    @ApplicationScoped
    public WebServer serverBean(final CommandReader commandReader, final WebServer.Configuration configuration) {
        if (!"serve".equals(commandReader.command())) {
            return new WebServer() {
                @Override
                public void close() {
                    // no-op
                }

                @Override
                public void await() {
                    // no-op
                }

                @Override
                public Configuration configuration() {
                    return configuration;
                }
            };
        }
        return WebServer.of(configuration);
    }
}
