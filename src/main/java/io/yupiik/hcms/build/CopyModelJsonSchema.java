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
package io.yupiik.hcms.build;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import io.yupiik.fusion.documentation.OpenRPC2Adoc;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class CopyModelJsonSchema implements Runnable {
    private final Path sourceBase;
    private final Path outputBase;

    public CopyModelJsonSchema(final Path sourceBase, final Path outputBase) {
        this.sourceBase = sourceBase;
        this.outputBase = outputBase;
    }

    @Override
    public void run() {
        try (final var in = Files.newBufferedReader(Path.of("target/classes/META-INF/fusion/json/schemas.json"));
                final var jsonMapper = new JsonMapperImpl(List.of(), e -> empty())) {
            final var schema = obj(jsonMapper.fromString(
                    Object.class, in.lines().collect(joining("\n")).replace("#/schemas/", "#/$defs/")));
            final var prepared = prepare(schema);
            Files.writeString(
                    Files.createDirectories(outputBase.resolve("schema")).resolve("model.jsonschema.json"),
                    jsonMapper.toString(prepared));

            Files.writeString(
                    Files.createDirectories(sourceBase.resolve("content/_partials/generated"))
                            .resolve("model.schema.adoc"),
                    // render first the model since it is the entry point
                    renderSchemas(Map.of("methods", Map.of(), "schemas", Map.of("Model", prepared)))
                                    .replace(". (Model) schema", "")
                            + '\n'
                            // then nested schemas
                            + renderSchemas(Map.of(
                                            "methods",
                                            Map.of(),
                                            "schemas",
                                            obj(prepared).get("$defs")))
                                    .replaceAll("(=== [^(]+) \\([^)]+\\) schema", "$1 schema")
                                    .replace("io.yupiik.hcms.service.model.json.Model.", ""));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String renderSchemas(final Map<String, Object> fakeOpenRpc) {
        final var value = new OpenRPC2Adoc(Map.of()).convert(fakeOpenRpc, null);
        return value.substring(value.indexOf("== Schemas\n") + "== Schemas\n".length());
    }

    private Object prepare(final Map<String, Object> schema) {
        final var schemas = obj(schema.get("schemas"));
        final var result =
                obj(requireNonNull(schemas.get("io.yupiik.hcms.service.model.json.Model"), "missing Model schema"));
        result.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        result.put("title", "HCMS Model Schema.");
        result.put(
                "$defs",
                obj(schema.get("schemas")).entrySet().stream()
                        .filter(e -> e.getKey().startsWith("io.yupiik.hcms.service.model.json.Model."))
                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)));
        obj(result.get("properties"))
                .put(
                        "$schema",
                        Map.of("type", "string", "default", "https://yupiik.io/hcms/schema/model.jsonschema.json"));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> obj(final Object o) {
        return (Map<String, Object>) o;
    }
}
