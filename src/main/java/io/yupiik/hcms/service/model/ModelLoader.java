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
package io.yupiik.hcms.service.model;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.hcms.configuration.HCMSConfiguration;
import io.yupiik.hcms.service.model.json.Model;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

@DefaultScoped
public class ModelLoader {
    private final HCMSConfiguration configuration;
    private final JsonMapper jsonMapper;

    public ModelLoader(final HCMSConfiguration configuration, final JsonMapper jsonMapper) {
        this.configuration = configuration;
        this.jsonMapper = jsonMapper;
    }

    @Bean
    @ApplicationScoped
    public DynamicModel loader() {
        final var path = Path.of(configuration.modelLocation());

        if (Files.exists(path)) {
            final var reloadedModel = new ReloadedModel(path, jsonMapper);
            if (configuration.devMode()) {
                return reloadedModel;
            }

            final var frozen = reloadedModel.get();
            return () -> frozen;
        }

        if (!path.isAbsolute()) {
            final var in =
                    Thread.currentThread().getContextClassLoader().getResourceAsStream(configuration.modelLocation());
            if (in != null) {
                try (final var reader = new BufferedReader(new InputStreamReader(in, UTF_8))) {
                    final var model = jsonMapper.read(Model.class, reader);
                    return () -> model;
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        } // else an absolute path can't be in the classloader

        throw new IllegalArgumentException("Didn't find '" + configuration.modelLocation() + "'");
    }

    public interface DynamicModel extends Supplier<Model> {
        default void onReload(Consumer<Model> task) {
            // no-op
        }

        @Override
        Model get();
    }

    private static class ReloadedModel implements DynamicModel {
        private final Path path;
        private final JsonMapper jsonMapper;

        private final Collection<Consumer<Model>> onReload = new CopyOnWriteArrayList<>();
        private final Lock lock = new ReentrantLock();
        private volatile long lastLoaded = -2;
        private volatile Model current;

        private ReloadedModel(final Path path, final JsonMapper jsonMapper) {
            this.path = path;
            this.jsonMapper = jsonMapper;
            reload();
        }

        private void reload() {
            try {
                long last = Files.getLastModifiedTime(path).toMillis();
                if (last > lastLoaded) {
                    lock.lock();
                    last = Files.getLastModifiedTime(path).toMillis();
                    if (last > lastLoaded) {
                        try {
                            lastLoaded = last;
                            try (final var in = Files.newBufferedReader(path)) {
                                current = jsonMapper.read(Model.class, in);
                            }
                            onReload.forEach(c -> c.accept(current));
                        } finally {
                            lock.unlock();
                        }
                    }
                }
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Model get() {
            return current;
        }

        @Override
        public void onReload(final Consumer<Model> task) {
            onReload.add(task);
        }
    }
}
