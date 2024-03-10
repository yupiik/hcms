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
package io.yupiik.hcms.http;

import static io.yupiik.fusion.framework.build.api.http.HttpMatcher.PathMatching.EXACT;
import static io.yupiik.fusion.framework.build.api.http.HttpMatcher.PathMatching.STARTS_WITH;
import static io.yupiik.hcms.service.model.json.Model.JsonRpcMethodType.CRUD;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;
import static java.util.Map.entry;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import io.yupiik.fusion.documentation.OpenRPC2OpenAPI;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.http.HttpMatcher;
import io.yupiik.fusion.http.server.api.IOConsumer;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.http.server.api.WebServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.hcms.service.model.Entity;
import io.yupiik.hcms.service.model.ModelHandler;
import io.yupiik.hcms.service.model.ModelLoader;
import io.yupiik.hcms.service.model.json.Model;
import java.io.IOException;
import java.io.Writer;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;

@ApplicationScoped
public class DocEndpoint {
    private final JsonMapper jsonMapper;
    private final ModelHandler modelHandler;
    private final int port;

    private volatile String title;
    private volatile Response openrpc;
    private volatile Response openapi;
    private final Map<String, byte[]> swaggerUIResources = new HashMap<>();
    private volatile boolean enableOpenAPI;

    public DocEndpoint(
            final ModelLoader.DynamicModel model,
            final JsonMapper jsonMapper,
            final ModelHandler modelHandler,
            final WebServer webServer) {
        this.jsonMapper = jsonMapper;
        this.modelHandler = modelHandler;

        if (model == null) { // subclass case
            port = -1;
            return;
        }

        this.port = webServer.configuration().port();
        reload(model.get());
        model.onReload(this::reload);
        try {
            preloadSwaggerUI();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @HttpMatcher(methods = "GET", pathMatching = STARTS_WITH, path = "/swagger-ui/")
    public Response getSwaggerUI(final Request request) {
        if (!enableOpenAPI) {
            return Response.of().status(404).header("content-type", "text/tml").build();
        }

        final var path = request.path().substring("/swagger-ui/".length());
        final var builder = Response.of();
        if (path.isBlank() || "index.html".equals(path)) {
            return builder.status(200)
                    .header("content-type", "text/html")
                    .body("<!DOCTYPE html>\n" + "<html lang=\"en\">\n"
                            + "  <head>\n"
                            + "    <meta charset=\"UTF-8\">\n"
                            + "      <link rel=\"icon\" type=\"image/x-icon\" href=\"/swagger-ui/img/yupiik.png\">"
                            + "    <title>"
                            + title + "</title>\n"
                            + "    <link rel=\"stylesheet\" type=\"text/css\" href=\"/swagger-ui/css/swagger-ui.css\" />\n"
                            + "    <style>\n"
                            + "    .topbar { display: none; }\n"
                            + "    body {\n"
                            + "     --yupiik-blue: #007bff;\n"
                            + "     --yupiik-blue-light: #ECF5FF;\n"
                            + "    }\n"
                            + "    .swagger-ui .opblock.opblock-post .opblock-summary,\n"
                            + "    .swagger-ui .opblock.opblock-post,\n"
                            + "    .swagger-ui .response-control-media-type--accept-controller select {\n"
                            + "      border-color: var(--yupiik-blue) !important;\n"
                            + "    }\n"
                            + "    .swagger-ui .response-control-media-type__accept-message {\n"
                            + "      color: var(--yupiik-blue) !important;\n"
                            + "    }\n"
                            + "    .swagger-ui .opblock .opblock-summary-method,\n"
                            + "    .swagger-ui .opblock.opblock-post .tab-header .tab-item.active h4 span:after {\n"
                            + "      background: var(--yupiik-blue) !important;\n"
                            + "    }\n"
                            + "    .swagger-ui .opblock.opblock-post {\n"
                            + "      background: var(--yupiik-blue-light) !important;\n"
                            + "    }\n"
                            + "    </style>\n"
                            + "  </head>\n"
                            + "  <body>\n"
                            + "    <div id=\"swagger-ui\"></div>\n"
                            + "    <script src=\"/swagger-ui/js/swagger-ui-bundle.js\" charset=\"UTF-8\"> </script>\n"
                            + "    <script src=\"/swagger-ui/js/swagger-ui-standalone-preset.js\" charset=\"UTF-8\"> </script>\n"
                            + "    <script>\n"
                            + "      window.onload = function() {\n"
                            + "        window.ui = SwaggerUIBundle({\n"
                            + "          url: \"/openapi.json\",\n"
                            + "          dom_id: '#swagger-ui',\n"
                            + "          deepLinking: true,\n"
                            + "          presets: [\n"
                            + "            SwaggerUIBundle.presets.apis,\n"
                            + "            SwaggerUIStandalonePreset,\n"
                            + "          ],\n"
                            + "          plugins: [\n"
                            + "            SwaggerUIBundle.plugins.DownloadUrl,\n"
                            + "          ],\n"
                            + "          layout: \"StandaloneLayout\",\n"
                            + "          requestInterceptor: function (request) {\n"
                            + "            if (request.loadSpec) {\n"
                            + "              return request;\n"
                            + "            }\n"
                            + "\n"
                            + "            var method = request.url.substring(request.url.lastIndexOf('/jsonrpc/') + '/jsonrpc/'.length);\n"
                            + "            var requestWithRightUrl = Object.assign(request, {\n"
                            + "              url: request.url.substring(0, request.url.length - method.length - 1),\n"
                            + "            });\n"
                            + "\n"
                            + "            var basic = request.headers.Authorization;\n"
                            + "            if (basic && basic.indexOf('Basic ') === 0) {\n"
                            + "              var up = atob(basic.substring('basic '.length));\n"
                            + "              var sep = up.indexOf(':');\n"
                            + "              var username = up.substring(0, sep);\n"
                            + "              var password = up.substring(sep + 1);\n"
                            + "              return fetch('/jsonrpc', { method: 'POST', body: JSON.stringify({ jsonrpc: '2.0', method: 'hcms.security.login', params: { username, password } }), headers: { 'accept': 'application/json;charset=utf-8', 'content-type': 'application/json;charset=utf-8' } })\n"
                            + "                .then(res => res.json())\n"
                            + "                .then(res => {\n"
                            + "                  return Object.assign(requestWithRightUrl, {\n"
                            + "                    headers: Object.assign(request.headers, { Authorization: 'Bearer ' + (res.result || {}).access_token }),\n"
                            + "                  });\n"
                            + "                });\n"
                            + "            }\n"
                            + "\n"
                            + "            return requestWithRightUrl;\n"
                            + "          },\n"
                            + "        });\n"
                            + "      };\n"
                            + "    </script>\n"
                            + "  </body>\n"
                            + "</html>\n")
                    .build();
        }

        final var encoding = request.header("accept-encoding");
        byte[] content = null;
        if (encoding != null && encoding.contains("gzip")) {
            content = swaggerUIResources.get(path + ".gz");
            if (content != null) {
                builder.header("content-encoding", "gzip");
            }
        }
        if (content == null) {
            content = swaggerUIResources.get(path);
        }

        if (content != null) {
            return builder.status(200)
                    .header(
                            "content-type",
                            path.endsWith(".js")
                                    ? "application/javascript"
                                    : (path.endsWith(".css")
                                            ? "text/css"
                                            : (path.endsWith(".png") ? "image/png" : "application/octet-stream")))
                    .body(HttpRequest.BodyPublishers.ofByteArray(content))
                    .build();
        }

        return builder.status(404).build();
    }

    @HttpMatcher(methods = "GET", pathMatching = EXACT, path = "/openrpc.json")
    public Response getOpenRPC() {
        return openrpc;
    }

    @HttpMatcher(methods = "GET", pathMatching = EXACT, path = "/openapi.json")
    public Response getOpenAPI() {
        return openapi;
    }

    private void preloadSwaggerUI() throws IOException {
        final var loader = Thread.currentThread().getContextClassLoader();
        final var pom = loader.getResourceAsStream("META-INF/maven/org.webjars/swagger-ui/pom.properties");
        if (pom == null) {
            return;
        }
        final var pomProps = new Properties();
        try (pom) {
            pomProps.load(pom);
        }

        final var version = pomProps.getProperty("version", "5.10.3");
        final var versionPrefix = "META-INF/resources/webjars/swagger-ui/" + version + '/';
        loadSwaggerResource(loader, "img/yupiik.png", "assets/yupiik.png");
        loadSwaggerResource(loader, "css/swagger-ui.css", versionPrefix + "swagger-ui.css");
        loadSwaggerResource(loader, "js/swagger-ui-bundle.js", versionPrefix + "swagger-ui-bundle.js");
        loadSwaggerResource(
                loader, "js/swagger-ui-standalone-preset.js", versionPrefix + "swagger-ui-standalone-preset.js");
    }

    private void loadSwaggerResource(final ClassLoader loader, final String relocated, final String rawRes)
            throws IOException {
        final var res = loader.getResourceAsStream(rawRes);
        if (res != null) {
            try (res) {
                swaggerUIResources.put(relocated, res.readAllBytes());
            }
            if (!relocated.endsWith(".gz")) {
                loadSwaggerResource(loader, relocated + ".gz", rawRes + ".gz");
            }
        }
    }

    private void reload(final Model model) {
        enableOpenAPI = model.enableOpenAPI() == null || model.enableOpenAPI();
        final boolean enableOpenRPC = model.enableOpenRPC() == null || model.enableOpenRPC();

        final var openRPC = new TreeMap<>();

        if (enableOpenAPI || enableOpenRPC) {
            computeOpenRPC(model, openRPC);
        }

        final var jsonOpenRPC = jsonMapper.toString(openRPC);
        final var sanitizedOpenRPC = jsonOpenRPC
                // retranslate schema to openrpc location
                .replace("#/$defs/", "#/components/schema/");
        this.openrpc = enableOpenRPC
                ? Response.of()
                        .status(200)
                        .header("Pragma", "no-cache")
                        .header("Cache-Control", "no-store")
                        .header("Content-Type", "application/json")
                        .body((IOConsumer<Writer>) io -> io.write(sanitizedOpenRPC))
                        .build()
                : Response.of().status(404).build();

        if (enableOpenAPI) {
            computeOpenAPI(model, jsonOpenRPC);
        } else {
            this.openapi = Response.of().status(404).build();
        }
    }

    @SuppressWarnings("unchecked")
    private void computeOpenRPC(final Model model, final TreeMap<Object, Object> openRPC) {
        openRPC.putAll(Map.of( // defaults
                "openrpc",
                "1.2.1",
                "info",
                Map.of(
                        "version", "1.0.0",
                        "title", "HCMS API")));
        if (model.partialOpenRPC() != null) { // override with user data
            openRPC.putAll(new TreeMap<>(model.partialOpenRPC().entrySet().stream()
                    .collect(toMap(
                            Map.Entry::getKey,
                            e -> e.getValue() instanceof Map<?, ?> m ? new TreeMap<>(m) : e.getValue()))));
        }
        title = ((Map<String, Object>) openRPC.get("info"))
                .getOrDefault("title", "API")
                .toString();

        // complete with actually deployed virtual methods
        openRPC.putAll(buildMethods(model));
    }

    @SuppressWarnings("unchecked")
    private void computeOpenAPI(final Model model, final String jsonOpenRPC) {
        final var openApiConverterConfiguration = new HashMap<String, String>();
        boolean hasServer = false;
        if (model.partialOpenRPC() != null) {
            if (model.partialOpenRPC().get("servers") instanceof List<?> servers && !servers.isEmpty()) {
                servers.stream().map(s -> (Map<String, Object>) s).forEach(s -> {
                    openApiConverterConfiguration.put(
                            s.get("name") + ".url", s.get("url").toString());
                    openApiConverterConfiguration.put(
                            s.get("name") + ".description",
                            s.getOrDefault("description", "").toString());
                });
                hasServer = true;
            }
            if (model.partialOpenRPC().get("info") instanceof Map<?, ?> map) {
                final var info = (Map<String, Object>) map;
                openApiConverterConfiguration.put(
                        "info.title", info.getOrDefault("title", "").toString());
                openApiConverterConfiguration.put(
                        "info.description", info.getOrDefault("description", "").toString());
                openApiConverterConfiguration.put(
                        "info.version", info.getOrDefault("version", "").toString());
                if (info.containsKey("termsOfService")) {
                    openApiConverterConfiguration.put(
                            "info.termsOfService", info.get("termsOfService").toString());
                }
                if (info.containsKey("license")) {
                    openApiConverterConfiguration.put(
                            "info.license", info.get("license").toString());
                }
                if (info.containsKey("contact")) {
                    openApiConverterConfiguration.put(
                            "info.contact", info.get("contact").toString());
                }
            }
        }
        openApiConverterConfiguration.putIfAbsent("info.title", "HCMS API");
        openApiConverterConfiguration.putIfAbsent("info.description", "");
        openApiConverterConfiguration.putIfAbsent("info.version", "1.0.0");
        if (!hasServer) {
            openApiConverterConfiguration.put("server.url", "http://localhost:" + port + "/jsonrpc");
            openApiConverterConfiguration.put("server.description", "Server");
        }

        // a round trip to drop JsonSchema objects we kept since converter needs Map only
        // plus some manipulation to comply a plain openrpc to the internal fusion model to reuse openrpc2openapi
        // converter for now
        final var copyOpenRPC = new HashMap<>((Map<String, Object>) jsonMapper.fromString(Object.class, jsonOpenRPC));
        final var directSchemas = (Map<String, Object>)
                ((Map<String, Object>) copyOpenRPC.getOrDefault("components", Map.of())).get("schemas");
        copyOpenRPC.put(
                "schemas",
                directSchemas.entrySet().stream()
                        .map(e -> entry(
                                e.getKey(),
                                e.getValue() instanceof Map<?, ?> schema ? inlineSchemaType(schema) : e.getValue()))
                        .collect(toMap(Map.Entry::getKey, e -> {
                            final var schema = new HashMap<>((Map<String, Object>) e.getValue());
                            schema.putIfAbsent("title", e.getKey() + " entity.");
                            return schema;
                        })));
        if (copyOpenRPC.get("methods") instanceof List<?> methods) {
            copyOpenRPC.put(
                    "methods",
                    new TreeMap<>(methods.stream()
                            .collect(toMap(m -> ((Map<String, Object>) m).get("name"), this::inlineSchemaType))));
        }
        final var openapi = new OpenRPC2OpenAPI(openApiConverterConfiguration) {
            @Override
            protected Map<String, Object> toMethod(final Map<String, ?> voidSchema, final Map<String, Object> method) {
                final var mtd = super.toMethod(voidSchema, method);
                final var name = method.get("name").toString();
                if (name.startsWith("hcms.")) {
                    mtd.put("tags", List.of("HCMS"));
                } else {
                    final int sep = name.lastIndexOf('.');
                    if (sep > 0) {
                        mtd.put("tags", List.of(name.substring(0, sep)));
                    }
                }
                return mtd;
            }

            @Override
            protected Map<String, Object> process(final Map<String, Object> out) {
                final var components = new HashMap<>((Map<String, Object>) out.get("components"));
                components.put(
                        "securitySchemes",
                        Map.of(
                                "hcms",
                                Map.of(
                                        "type", "http",
                                        // fake basic to get username/password form
                                        // without overriding a component
                                        // -> see requestInterceptor for the handling
                                        "scheme", "basic")));
                out.put("components", components);
                out.put("security", List.of(Map.of("hcms", List.of())));
                return out;
            }

            @Override
            protected String preProcessInput(final String input) {
                return input.replace("\"#/$defs/", "\"#/components/schemas/");
            }
        }.convert(copyOpenRPC, jsonMapper);
        this.openapi = Response.of()
                .status(200)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-store")
                .header("Content-Type", "application/json")
                .body((IOConsumer<Writer>) io -> io.write(openapi))
                .build();
    }

    @SuppressWarnings("unchecked")
    private Object inlineSchemaType(final Object schema) {
        if (schema instanceof Map<?, ?> map) {
            return ((Map<String, Object>) map)
                    .entrySet().stream()
                            .flatMap(e -> switch (e.getKey()) {
                                case "type" -> e.getValue() instanceof List<?> list
                                        ? switch (list.size()) {
                                            case 0 -> Stream.of(e);
                                            case 1 -> Stream.of(entry(
                                                    e.getKey(), list.get(0).toString()));
                                            default -> list.contains("null")
                                                    ? Stream.<Map.Entry<String, Object>>of(
                                                            entry("nullable", true),
                                                            entry(
                                                                    e.getKey(),
                                                                    list.stream()
                                                                            .filter(it -> !Objects.equals("null", it))
                                                                            .findFirst()
                                                                            .map(String::valueOf)
                                                                            .orElse("null")))
                                                    : Stream.of(entry(
                                                            e.getKey(),
                                                            list.get(0).toString()));
                                        }
                                        : Stream.of(e);
                                default -> Stream.of(e);
                            })
                            .map(e -> entry(e.getKey(), inlineSchemaType(e.getValue())))
                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, TreeMap::new));
        }
        if (schema instanceof List<?> list) {
            return list.stream().map(this::inlineSchemaType).toList();
        }
        return schema;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildMethods(final Model model) {
        final var methods = new ArrayList<Map<String, Object>>();
        final var schemas = new TreeMap<String, Object>();

        // standard methods - "programmatically"
        try {
            final var resources =
                    Thread.currentThread().getContextClassLoader().getResources("META-INF/fusion/jsonrpc/openrpc.json");
            while (resources.hasMoreElements()) {
                try (final var in = resources.nextElement().openStream()) {
                    final var partial = (Map<String, Object>) jsonMapper.fromString(
                            Object.class,
                            new String(in.readAllBytes(), UTF_8)
                                    // this could/should be moved to rewriting the object tree visiting it but this is
                                    // easier for now
                                    .replace("#/schemas/", "#/components/schemas/"));
                    ofNullable(partial.get("schemas"))
                            .filter(Map.class::isInstance)
                            .map(Map.class::cast)
                            .ifPresent(schemas::putAll);
                    ofNullable(partial.get("methods"))
                            .filter(Map.class::isInstance)
                            .map(Map.class::cast)
                            .map(Map::values)
                            .ifPresent(methods::addAll);
                }
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }

        // create virtual schemas
        final var entities = modelHandler.entities();
        if (entities != null) {
            schemas.putAll(entities.values().stream().collect(toMap(Entity::name, Entity::schema)));

            // create virtual methods
            final var renderers = Stream.concat(
                            Stream.of(new String[] {null}),
                            modelHandler.availableRenderers().keySet().stream().sorted())
                    .toList();
            methods.addAll(model.jsonRpcMethods().stream()
                    .flatMap(m -> switch (m.type() == null ? CRUD : m.type()) {
                        case CRUD -> Stream.of(
                                findAll(renderers, entities, m),
                                findById(renderers, entities, m),
                                deleteById(entities, m),
                                create(entities, m),
                                update(entities, m));
                        case FIND_ALL -> Stream.of(findAll(renderers, entities, m));
                        case FIND_BY_ID -> Stream.of(findById(renderers, entities, m));
                        case DELETE_BY_ID -> Stream.of(deleteById(entities, m));
                        case CREATE -> Stream.of(create(entities, m));
                        case UPDATE -> Stream.of(update(entities, m));
                    })
                    .toList());
        }

        return Map.of(
                "components",
                Map.of("schemas", schemas),
                "methods",
                methods.stream()
                        .sorted(comparing(m -> m.get("name").toString()))
                        .toList());
    }

    private Map<String, Object> findAll(
            final List<String> renderers, final Map<String, Entity> entities, final Model.JsonRpcMethod method) {
        final var entity = entities.get(method.entityName());
        return Map.of(
                "name",
                method.entityName() + ".findAll",
                "description",
                method.description() != null && !method.description().isBlank()
                        ? method.description()
                        : "Find all '" + entity.name() + "'.",
                "params",
                Stream.concat(
                                Stream.concat(
                                        Stream.of(
                                                Map.of(
                                                        "name", "page",
                                                        "description", "The page to fetch, starting at index 1.",
                                                        "schema", Map.of("type", "number")),
                                                Map.of(
                                                        "name",
                                                        "pageSize",
                                                        "description",
                                                        "How many items to return at one time, between 1 and 50.",
                                                        "schema",
                                                        Map.of("type", "number"))),
                                        Stream.concat(
                                                renderersParam(
                                                        renderers,
                                                        entity.schema()
                                                                .properties()
                                                                .keySet()),
                                                fieldsParam(entity.schema()
                                                        .properties()
                                                        .keySet()))),
                                Stream.concat(
                                        entity.allowedFilterKeys() == null
                                                        || entity.allowedFilterKeys()
                                                                .isEmpty()
                                                ? Stream.empty()
                                                : Stream.of(
                                                        Map.of(
                                                                "name",
                                                                "filters",
                                                                "description",
                                                                "List of filters to apply on the entity '"
                                                                        + method.entityName() + "'",
                                                                "schema",
                                                                Map.of(
                                                                        "type",
                                                                        "object",
                                                                        "additionalProperties",
                                                                        false,
                                                                        "properties",
                                                                        new TreeMap<>(
                                                                                entity.allowedFilterKeys().stream()
                                                                                        .collect(
                                                                                                toMap(
                                                                                                        identity(),
                                                                                                        k -> Map.of(
                                                                                                                "type",
                                                                                                                "object",
                                                                                                                "properties",
                                                                                                                Map.of(
                                                                                                                        "operator",
                                                                                                                        Map
                                                                                                                                .of(
                                                                                                                                        "type",
                                                                                                                                        "string",
                                                                                                                                        "enum",
                                                                                                                                        entity
                                                                                                                                                .allowedWhereOperators()
                                                                                                                                                .stream()
                                                                                                                                                .sorted()
                                                                                                                                                .toList()),
                                                                                                                        "value",
                                                                                                                        entity.schema()
                                                                                                                                .properties()
                                                                                                                                .get(
                                                                                                                                        k))))))))),
                                        entity.allowedSortKeys() == null
                                                        || entity.allowedSortKeys()
                                                                .isEmpty()
                                                ? Stream.empty()
                                                : Stream.of(Map.of(
                                                        "name",
                                                        "sortBy",
                                                        "description",
                                                        "Sorting to apply on the result set for entity '"
                                                                + method.entityName() + "'",
                                                        "schema",
                                                        Map.of(
                                                                "type",
                                                                "object",
                                                                "additionalProperties",
                                                                false,
                                                                "properties",
                                                                Map.of(
                                                                        "name",
                                                                        Map.of(
                                                                                "type",
                                                                                "string",
                                                                                "enum",
                                                                                entity.allowedSortKeys().stream()
                                                                                        .sorted()
                                                                                        .toList()),
                                                                        "direction",
                                                                        Map.of(
                                                                                "type",
                                                                                "string",
                                                                                "enum",
                                                                                List.of("ASC", "DESC"))))))))
                        .toList(),
                "result",
                Map.of(
                        "name",
                        "page",
                        "schema",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(
                                        "total",
                                        Map.of(
                                                "type", "integer",
                                                "description", "Size of the complete dataset (all pages)."),
                                        "items",
                                        Map.of("type", "array", "items", entity.schema())))));
    }

    private Map<String, Object> findById(
            final List<String> renderers, final Map<String, Entity> entities, final Model.JsonRpcMethod method) {
        final var entity = entities.get(method.entityName());
        return Map.of(
                "name",
                method.entityName() + ".findById",
                "description",
                method.description() != null && !method.description().isBlank()
                        ? method.description()
                        : "Find by id '" + entity.name() + "'.",
                "params",
                Stream.concat(
                                entity.identifiers().stream()
                                        .map(id -> Map.of(
                                                "name",
                                                id,
                                                "description",
                                                "`" + id + "` value.",
                                                "schema",
                                                entity.schema().properties().get(id))),
                                Stream.concat(
                                        renderersParam(
                                                renderers,
                                                entity.schema().properties().keySet()),
                                        fieldsParam(entity.schema().properties().keySet())))
                        .toList(),
                "result",
                Map.of("name", "entity", "schema", entity.schema()));
    }

    private Stream<Map<String, Object>> renderersParam(
            final List<String> availableRenderers, final Collection<String> fields) {
        if (availableRenderers.isEmpty()) {
            return Stream.of();
        }
        return Stream.of(Map.of(
                "name",
                "renderers",
                "description",
                "Name of renderer to use per field when desired - fully optional, default will just return the raw data.",
                "schema",
                Map.of(
                        "type",
                        "object",
                        "nullable",
                        true,
                        "properties",
                        fields.stream()
                                .collect(toMap(
                                        identity(),
                                        name -> Map.of(
                                                "type",
                                                "string",
                                                "nullable",
                                                true,
                                                "enum",
                                                availableRenderers,
                                                "description",
                                                "Renderer to use to render field '" + name + "'"))))));
    }

    private Stream<Map<String, Object>> fieldsParam(final Collection<String> fields) {
        return Stream.of(Map.of(
                "name",
                "fields",
                "description",
                "Name of the fields to fetch.",
                "schema",
                Map.of("type", "array", "nullable", true, "items", Map.of("type", "string", "enum", fields))));
    }

    private Map<String, Object> deleteById(final Map<String, Entity> entities, final Model.JsonRpcMethod method) {
        final var entity = entities.get(method.entityName());
        return Map.of(
                "name",
                method.entityName() + ".deleteById",
                "description",
                method.description() != null && !method.description().isBlank()
                        ? method.description()
                        : "Find by id '" + entity.name() + "'.",
                "params",
                entity.identifiers().stream()
                        .map(id -> Map.of(
                                "name",
                                id,
                                "description",
                                "`" + id + "` value.",
                                "schema",
                                entity.schema().properties().get(id)))
                        .toList(),
                "result",
                Map.of(
                        "name",
                        "status",
                        "schema",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("status", Map.of("type", "boolean", "default", true)))));
    }

    private Map<String, Object> create(final Map<String, Entity> entities, final Model.JsonRpcMethod method) {
        final var entity = entities.get(method.entityName());
        return Map.of(
                "name",
                method.entityName() + ".create",
                "description",
                method.description() != null && !method.description().isBlank()
                        ? method.description()
                        : "Create an instance of '" + entity.name() + "'.",
                "params",
                entity.schema().properties().entrySet().stream()
                        .filter(e -> entity.revisionProperty() == null
                                || !Objects.equals(entity.revisionProperty(), e.getKey()))
                        .filter(e -> !entity.autoGeneratedIds()
                                || !entity.identifiers().contains(e.getKey()))
                        .map(e -> Map.of(
                                "name", e.getKey(),
                                "description", "`" + e.getKey() + "` value.",
                                "schema", e.getValue()))
                        .toList(),
                "result",
                Map.of("name", "entity", "schema", entity.schema()));
    }

    private Map<String, Object> update(final Map<String, Entity> entities, final Model.JsonRpcMethod method) {
        final var entity = entities.get(method.entityName());
        return Map.of(
                "name",
                method.entityName() + ".update",
                "description",
                method.description() != null && !method.description().isBlank()
                        ? method.description()
                        : "Update an instance of '" + entity.name() + "'.",
                "params",
                entity.schema().properties().entrySet().stream()
                        .map(e -> Map.of(
                                "name", e.getKey(),
                                "description", "`" + e.getKey() + "` value.",
                                "schema", e.getValue()))
                        .toList(),
                "result",
                Map.of("name", "entity", "schema", entity.schema()));
    }
}
