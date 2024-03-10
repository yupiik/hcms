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
package io.yupiik.hcms.jsonrpc.extension;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import io.yupiik.fusion.jsonrpc.JsonRpcRegistry;
import io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod;
import io.yupiik.hcms.service.model.ModelHandler;
import io.yupiik.hcms.service.model.ModelLoader;
import io.yupiik.hcms.service.model.json.Model;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@DefaultScoped
public class ExtendedJsonRpcRegistry extends JsonRpcRegistry {
    private final ModelHandler converter;
    private final List<JsonRpcMethod> standardMethods;
    private volatile JsonRpcRegistry delegate;

    public ExtendedJsonRpcRegistry(
            final List<JsonRpcMethod> methods,
            final ModelLoader.DynamicModel dynamicModel,
            final ModelHandler converter) {
        super(List.of());
        this.standardMethods = methods;
        this.converter = converter;
        reload(dynamicModel.get());
        dynamicModel.onReload(this::reload);
    }

    @Override
    public Map<String, JsonRpcMethod> methods() {
        return delegate.methods();
    }

    private void reload(final Model model) {
        delegate = new JsonRpcRegistry(Stream.concat(standardMethods.stream(), converter.toJsonRpc(model))
                .toList());
    }

    @DefaultScoped
    public static class Register {
        @Bean
        @Order(2000) // force the exact type we override in the container
        public JsonRpcRegistry overrideDefaultRegistry(final ExtendedJsonRpcRegistry registry) {
            return registry;
        }
    }
}
