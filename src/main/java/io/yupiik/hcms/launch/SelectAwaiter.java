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

import io.yupiik.fusion.cli.CliAwaiter;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.RuntimeContainerImpl;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.container.context.ApplicationFusionContext;
import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.main.Args;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.main.Awaiter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.order.Order;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Since we host primarly a server we have the server awaiter
 * but we also provide a few utility commands so we have cli awaiter.
 * <p>
 * It is not great to have both at once so we drop the one we don't intend to use.
 * Coupled with {@link io.yupiik.hcms.server.ServerBean} it enables to only execute the part we want.
 */
@DefaultScoped
public class SelectAwaiter {
    public void onEvent(
            @OnEvent @Order(0) final Start start,
            final CommandReader commandReader,
            final Optional<Args> args,
            final RuntimeContainer container) {
        final var beans = container.getBeans().getBeans();
        if (args.isPresent() && !"serve".equals(commandReader.command())) {
            beans.remove(Awaiter.class); // server one

            // for now we assume --hcms-command xxxx are the 2 first args
            final var copy = new ArrayList<>(args.orElseThrow().args());
            copy.remove("--hcms-command");
            final var overridenArgs = new Args(copy);
            beans.put(
                    Args.class,
                    List.of(new ProvidedInstanceBean<>(DefaultScoped.class, Args.class, () -> overridenArgs)));

            // override default config with the new args and reset Configuration instance for HCMSConfiguration
            if (!copy.isEmpty()) {
                final var argsConfigSource = new ArgsConfigSource(copy.subList(1, copy.size()));
                beans.put(
                        ArgsConfigSource.class,
                        List.of(new ProvidedInstanceBean<>(
                                DefaultScoped.class, ArgsConfigSource.class, () -> argsConfigSource)));

                // reset configuration instance to use the reformatted options (ArgsConfigSource)
                ((ApplicationFusionContext) container
                                .getContexts()
                                .findContext(ApplicationScoped.class)
                                .orElseThrow())
                        .clean(beans.get(Configuration.class).getFirst());

                if (container instanceof RuntimeContainerImpl impl) {
                    impl.clearCache();
                }
            }
        } else { // server mode
            beans.remove(CliAwaiter.class);
        }
    }
}
