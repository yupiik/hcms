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
package io.yupiik.hcms.test;

import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.fusion.persistence.api.ContextLessDatabase;
import io.yupiik.fusion.persistence.api.TransactionManager;
import io.yupiik.hcms.configuration.HCMSConfiguration;
import io.yupiik.hcms.service.persistence.entity.User;
import io.yupiik.hcms.service.security.PasswordEncoder;

@DefaultScoped
public class InitTestUsers {
    public void onStart(
            @OnEvent @Order(5_000) final Start start,
            final HCMSConfiguration configuration,
            final RuntimeContainer container) {
        if (!"-".equals(configuration.database().url())) {
            try (final var i = container.lookup(Init.class)) {
                i.instance().run();
            }
        }
    }

    @DefaultScoped
    public static class Init {
        private final TransactionManager tx;
        private final ContextLessDatabase db;
        private final PasswordEncoder encoder;

        public Init(final TransactionManager tx, final ContextLessDatabase db, final PasswordEncoder encoder) {
            this.tx = tx;
            this.db = db;
            this.encoder = encoder;
        }

        public void run() {
            tx.write(c -> db.insert(
                    c, new User("test@app.com", "test@app.com", "Test", "App", encoder.toDatabase("@dm1n"), true)));
        }
    }
}
