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

import static io.yupiik.hcms.jsonrpc.extension.ExtendedJsonRpcHandler.findConnection;
import static io.yupiik.hcms.service.model.json.Model.JsonRpcMethodType.CREATE;
import static io.yupiik.hcms.service.model.json.Model.JsonRpcMethodType.CRUD;
import static io.yupiik.hcms.service.model.json.Model.JsonRpcMethodType.DELETE_BY_ID;
import static io.yupiik.hcms.service.model.json.Model.JsonRpcMethodType.FIND_ALL;
import static io.yupiik.hcms.service.model.json.Model.JsonRpcMethodType.FIND_BY_ID;
import static io.yupiik.hcms.service.model.json.Model.JsonRpcMethodType.UPDATE;
import static io.yupiik.hcms.service.model.json.Model.JsonSchemaFormat.date_time;
import static io.yupiik.hcms.service.model.json.Model.JsonSchemaType.nullValue;
import static io.yupiik.hcms.service.model.json.Model.JsonSchemaType.number;
import static io.yupiik.hcms.service.model.json.Model.JsonSchemaType.object;
import static io.yupiik.hcms.service.model.json.Model.JsonSchemaType.string;
import static java.util.Comparator.comparing;
import static java.util.Locale.ROOT;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.schema.validation.JsonSchemaValidatorFactory;
import io.yupiik.fusion.json.schema.validation.ValidationResult;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.jsonrpc.impl.DefaultJsonRpcMethod;
import io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod;
import io.yupiik.fusion.jwt.Jwt;
import io.yupiik.fusion.persistence.api.PersistenceException;
import io.yupiik.fusion.persistence.api.SQLFunction;
import io.yupiik.fusion.persistence.api.TransactionManager;
import io.yupiik.hcms.configuration.HCMSConfiguration;
import io.yupiik.hcms.service.model.json.Model;
import io.yupiik.hcms.service.naming.NameMapper;
import io.yupiik.hcms.service.persistence.DatabaseLoader;
import io.yupiik.hcms.service.renderer.Renderer;
import io.yupiik.hcms.service.security.SecurityHandler;
import io.yupiik.hcms.service.sql.SQLBiConsumer;
import io.yupiik.hcms.service.sql.SQLConsumer;
import io.yupiik.hcms.service.tracing.ClientSpanService;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedCollection;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Stream;

@ApplicationScoped
public class ModelHandler {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final Set<String> defaultWhereOperators =
            Set.of("=", "<", ">", ">=", "<=", "<>", "like", "ilike", "not like", "not ilike");

    private final TransactionManager transactionManager;
    private final NameMapper nameMapper;
    private final DatabaseLoader databaseLoader;
    private final SecurityHandler securityHandler;
    private final ClientSpanService spans;
    private final JsonMapper jsonMapper;
    private final Map<String, Renderer> renderers;
    private final HCMSConfiguration configuration;

    private final JsonSchemaValidatorFactory validatorFactory = new JsonSchemaValidatorFactory();
    private final ValidationResult validationOk = new ValidationResult(List.of());

    private volatile Map<String, Entity> entities;

    public ModelHandler(
            final HCMSConfiguration configuration,
            final TransactionManager transactionManager,
            final NameMapper nameMapper,
            final DatabaseLoader databaseLoader,
            final SecurityHandler securityHandler,
            final ClientSpanService spans,
            final JsonMapper jsonMapper,
            final List<Renderer> renderers) {
        this.transactionManager = transactionManager;
        this.nameMapper = nameMapper;
        this.jsonMapper = jsonMapper;
        this.databaseLoader = databaseLoader;
        this.securityHandler = securityHandler;
        this.spans = spans;
        this.configuration = configuration;
        this.renderers = renderers == null
                ? Map.of()
                : renderers.stream()
                        .filter(Predicate.not(
                                r -> configuration.disabledRenderers().contains(r.name())))
                        .collect(toMap(Renderer::name, identity()));
    }

    public Map<String, Renderer> availableRenderers() {
        return renderers;
    }

    public Map<String, Entity> entities() {
        return entities;
    }

    public Stream<JsonRpcMethod> toJsonRpc(final Model model) {
        if (model.jsonRpcMethods() == null || model.jsonRpcMethods().isEmpty()) {
            logger.info(() -> "No JSON-RPC method in the configuration");
            return Stream.empty();
        }

        if (model.sql() != null && !model.sql().isEmpty()) {
            logger.info(() -> "Executing scripts:\n" + String.join("\n", model.sql()));
            databaseLoader.execute(model.sql(), configuration.databaseInit().ignoreErrors());
        }

        final var entities = ofNullable(model.entities()).orElse(List.of()).stream()
                .peek(this::validateEntity)
                .map(this::toEntity)
                .peek(e -> logger.finest(() -> "Entity model: " + e))
                .peek(e -> logger.info(() -> "Processed entity '" + e.name() + "'"))
                .collect(toMap(Entity::name, identity(), (a, b) -> {
                    throw new IllegalArgumentException("Conflicting entities: '" + b + "'");
                }));
        final var registrations = model.jsonRpcMethods().stream()
                .flatMap(m -> toJsonRpcMethod(entities, m))
                .peek(m -> logger.info(() -> "Registering JSON-RPC method '" + m.name() + "'"));

        this.entities = entities; // save it after registration - in case it fails we want previous value

        return registrations;
    }

    private Stream<JsonRpcMethod> toJsonRpcMethod(final Map<String, Entity> entities, final Model.JsonRpcMethod model) {
        final var entity = entities.get(model.entityName());
        if (entity == null) {
            throw new IllegalArgumentException(
                    "Missing entity '" + model.entityName() + "' referenced by JSON-RPC method '" + model + "'");
        }

        return switch (model.type() == null ? CRUD : model.type()) {
            case CRUD -> Stream.of(FIND_BY_ID, FIND_ALL, DELETE_BY_ID, CREATE, UPDATE)
                    .map(type ->
                            new Model.JsonRpcMethod(type, model.entityName(), model.description(), model.security()))
                    .flatMap(m -> toJsonRpcMethod(entities, m));
            case FIND_BY_ID -> Stream.of(new ModelJsonRpcMethod(
                    entity.name() + ".findById",
                    securityHandler.compile(
                            model.security() == null ? null : model.security().view(), compileFindById(entity))));
            case DELETE_BY_ID -> Stream.of(new ModelJsonRpcMethod(
                    entity.name() + ".deleteById",
                    securityHandler.compile(
                            model.security() == null ? null : model.security().delete(), compileDeleteById(entity))));
            case FIND_ALL -> Stream.of(new ModelJsonRpcMethod(
                    entity.name() + ".findAll",
                    securityHandler.compile(
                            model.security() == null ? null : model.security().view(), compileFindAll(entity))));
            case CREATE -> Stream.of(new ModelJsonRpcMethod(
                    entity.name() + ".create",
                    securityHandler.compile(
                            model.security() == null ? null : model.security().create(), compileCreate(entity))));
            case UPDATE -> Stream.of(new ModelJsonRpcMethod(
                    entity.name() + ".update",
                    securityHandler.compile(
                            model.security() == null ? null : model.security().update(), compileUpdate(entity))));
        };
    }

    public boolean hasEntity(final String prefix) {
        return entities.containsKey(prefix);
    }

    /**
     * IMPORTANT: this is assumed called internally in a transactional context.
     *
     * @param entityPrefix the entity prefix in model.json.
     * @param requests     the requests to bulk.
     * @return the map with the request in key and response in value.
     */
    // todo: all the computed data should be cached in Entity
    public Map<Map<String, Object>, Map<String, Object>> findByIds(
            final Request request,
            final Connection connection,
            final String entityPrefix,
            final List<Map<String, Object>> requests,
            final JsonRpcMethod.Context context,
            final Map<String, Renderer> renderers,
            final List<String> fields) {
        final var entity = entities.get(entityPrefix);
        if (entity == null) {
            throw new JsonRpcException(400, "Invalid entity '" + entityPrefix + "'");
        }

        // this part could be "precompiled", it is not insanely slow so depends if we are proven using bulk often or not
        final var identifiers = entity.identifiers();

        final var ids = identifiers.stream()
                .sorted(comparing(entity.mapping().databaseToJson()::get))
                .collect(toMap(entity.mapping().databaseToJson()::get, identity(), (a, b) -> a, LinkedHashMap::new));

        final var projectionNames = new LinkedHashMap<String, String>(
                entity.mapping().databaseToJson().size());
        // id MUST be sorted since we extract them at the end
        projectionNames.putAll(ids);
        projectionNames.putAll(entity.mapping().databaseToJson().entrySet().stream()
                .filter(Predicate.not(e -> identifiers.contains(e.getValue())))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)));
        final var reversedProjectionNames =
                projectionNames.entrySet().stream().collect(toMap(Map.Entry::getValue, Map.Entry::getKey));

        final var idsPerRequest = requests.stream()
                .collect(toMap(
                        identity(), it -> findValuesFromParams(it.get("params"), identifiers, false), (a, b) -> b));

        final var implicitWhere = entity.implicitFiltering() == null
                ? null
                : prepareImplicitWhere(entity.implicitFiltering().view(), (int) requests.stream()
                        .map(idsPerRequest::get)
                        .mapToLong(Collection::size)
                        .sum());

        final var selectAllFields = fields == null || (fields.size() == 1 && Objects.equals(fields.getFirst(), "*"));
        final var selectedFieldsColumns = selectAllFields
                ? projectionNames.entrySet().stream()
                        .map(name -> selectColumn(entity.revisionProperty(), name))
                        .collect(joining(", "))
                : customColumns(
                        reversedProjectionNames,
                        entity.revisionProperty(),
                        // force identifiers since we need to dispatch then!
                        Stream.concat(identifiers.stream(), fields.stream())
                                .distinct()
                                .toList());
        final var findByIdSql = "select "
                + selectedFieldsColumns
                + " from "
                + entity.table()
                + " where "
                + requests.stream().map(it -> entity.whereIds()).collect(joining(") OR (", "(", ")"))
                + (implicitWhere != null ? '(' + implicitWhere.sql() + ')' : "")
                + (entity.revisionProperty() != null
                        ? identifiers.stream()
                                .map(k -> entity.mapping().jsonToDatabase().get(k))
                                .collect(joining(", ", " group by ", ""))
                        : "");
        final var names = selectAllFields
                ? entity.mapping().databaseToJson()
                : new TreeMap<>(fields.stream().collect(toMap(reversedProjectionNames::get, identity())));

        final var results = new HashMap<Map<String, Object>, Map<String, Object>>();
        try (final var stmt = connection.prepareStatement(findByIdSql)) {
            int index = 1;
            for (final var idItem : requests.stream()
                    .map(idsPerRequest::get)
                    .flatMap(Collection::stream)
                    .toList()) {
                stmt.setObject(index++, idItem);
            }
            if (implicitWhere != null) {
                final var ctx = new BindingContext(context, List.of());
                for (final var binder : implicitWhere.binders()) {
                    binder.accept(ctx, stmt);
                }
            }

            try (final var rset = stmt.executeQuery()) {
                while (rset.next()) {
                    final var idMap = new HashMap<String, Object>(ids.size());
                    final var idsEntries = ids.values().iterator();
                    for (int i = 1; i <= ids.size(); i++) {
                        idMap.put(idsEntries.next(), rset.getObject(i));
                    }
                    results.put(idMap, toMapResult(request, rset, names, List.of(), List.of(), renderers));
                }
            }
        } catch (final SQLException ex) {
            throw new JsonRpcException(500, "Can't find entity", null, ex);
        }

        return requests.stream()
                .collect(toMap(
                        identity(),
                        r -> {
                            // idsPerRequest has the list of id matching identifiers names
                            // so rebuild the object keying the result
                            final var idValues = idsPerRequest.get(r).iterator();
                            return results.get(identifiers.stream().collect(toMap(identity(), k -> idValues.next())));
                        },
                        (a, b) -> a));
    }

    private WhereClause prepareImplicitWhere(final Model.ImplicitFiltering filtering, final int firstIndex) {
        if (filtering == null || filtering.clause() == null) {
            return null;
        }

        final var index = new AtomicInteger(firstIndex);
        final var bindings = new ArrayList<SQLBiConsumer<BindingContext, PreparedStatement>>();
        // visit the clause and extract {{user.xxx}} as bindings
        final var sql = new HandlebarsCompiler((data, name) -> switch (name) {
                    case "user" -> Map.of("from", "user");
                    default -> { // assume user.$x
                        if (data instanceof Map<?, ?> map && Objects.equals("user", map.get("from"))) {
                            final var idx = index.getAndIncrement();
                            bindings.add((c, s) -> {
                                final var jwt = c.context().request().attribute(Jwt.class.getName(), Jwt.class);
                                final var value = jwt == null
                                        ? null
                                        : jwt.claim(name, Object.class).orElse(null);
                                if (value == null) {
                                    s.setNull(idx, Types.VARCHAR);
                                } else {
                                    s.setObject(idx, value);
                                }
                            });
                            yield "?";
                        }
                        throw new IllegalArgumentException("Invalid implicit binding: " + filtering);
                    }
                })
                .compile(new HandlebarsCompiler.CompilationContext(filtering.clause()))
                .render(Map.of());
        return new WhereClause(sql, bindings);
    }

    private Function<JsonRpcMethod.Context, CompletionStage<?>> compileFindAll(final Entity entity) {
        final var baseSql = entity.mapping().databaseToJson().entrySet().stream()
                .map(name -> selectColumn(entity.revisionProperty(), name))
                .collect(joining(", ", "select ", " from " + entity.table()));
        final var implicitWhere = entity.implicitFiltering() == null
                ? null
                : prepareImplicitWhere(entity.implicitFiltering().view(), 1);
        final var revisionGroupBy = entity.revisionProperty() != null
                ? entity.mapping().databaseToJson().entrySet().stream()
                        .filter(i -> entity.identifiers().contains(i.getValue()))
                        .map(Map.Entry::getKey)
                        .collect(joining(", ", " group by ", ""))
                : "";

        final var baseCountSql = "select count(*) as total from "
                + (entity.revisionProperty() != null ? "(" + baseSql + revisionGroupBy + ") vt" : entity.table());

        final var sortableKeys = entity.allowedSortKeys().stream()
                .collect(toMap(
                        identity(),
                        k -> requireNonNull(
                                entity.mapping().jsonToDatabase().get(k), () -> "Invalid sortable key '" + k + "'")));
        final var filterableKeys = entity.allowedFilterKeys().stream()
                .collect(toMap(
                        identity(),
                        k -> requireNonNull(
                                entity.mapping().jsonToDatabase().get(k), () -> "Invalid filterable key '" + k + "'")));
        final var allowedWhereOperators = entity.allowedWhereOperators();

        final var wherePrefix = "where" + (implicitWhere != null ? " (" + implicitWhere.sql() + ") AND " : "");
        final var defaultWherePrefix = (implicitWhere != null ? " where (" + implicitWhere.sql() + ")" : "");
        final var whereStartIndex =
                (implicitWhere == null ? 0 : implicitWhere.binders().size()) + 1;

        final var spanName = entity.name() + ".findAll";

        return ctx -> {
            int page = 1;
            int pageSize = 10;
            WhereClause whereClause =
                    implicitWhere == null ? null : new WhereClause(defaultWherePrefix, implicitWhere.binders());
            String orderByClause = "";
            Map<String, Renderer> renderers = Map.of();
            List<String> fields = null;
            if (ctx.params() instanceof List<?> list) {
                if (!list.isEmpty()) {
                    page = ((Number) list.getFirst()).intValue();
                }
                if (list.size() >= 2) {
                    pageSize = ((Number) list.get(1)).intValue();
                }

                // filter item is itself an objects like in map case
                if (list.size() >= 3 && list.get(2) instanceof Map<?, ?> filters) {
                    whereClause =
                            toWhereClause(filters, filterableKeys, allowedWhereOperators, whereStartIndex, wherePrefix);
                }

                // sort is an object too
                if (list.size() >= 3 && list.get(2) instanceof Map<?, ?> sort) {
                    orderByClause = toOrderByClause(sort, sortableKeys);
                }

                // renderers
                if (list.size() >= 4 && list.get(3) instanceof Map<?, ?> r) {
                    renderers = toRenderers(r);
                }

                // fields
                if (list.size() >= 5 && list.get(4) instanceof List<?> f) {
                    @SuppressWarnings("unchecked")
                    final var casted = (List<String>) f;
                    fields = casted;
                }
            } else if (ctx.params() instanceof Map<?, ?> map) {
                if (map.get("page") instanceof Number n) {
                    page = n.intValue();
                }
                if (map.get("pageSize") instanceof Number n) {
                    pageSize = n.intValue();
                }
                if (map.get("filters") instanceof Map<?, ?> filters) {
                    whereClause =
                            toWhereClause(filters, filterableKeys, allowedWhereOperators, whereStartIndex, wherePrefix);
                }
                if (map.get("sortBy") instanceof Map<?, ?> sort) {
                    orderByClause = toOrderByClause(sort, sortableKeys);
                }
                if (map.get("renderers") instanceof Map<?, ?> r) {
                    renderers = toRenderers(r);
                }
                if (map.get("fields") instanceof List<?> f) {
                    @SuppressWarnings("unchecked")
                    final var casted = (List<String>) f;
                    fields = casted;
                }
            } else {
                final var message = "Invalid request: " + ctx.params();
                logger.severe(message);
                throw new JsonRpcException(400, message);
            }

            final var where = whereClause == null ? "" : whereClause.sql();
            final var fullWhere = where + revisionGroupBy + orderByClause + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";

            final var selectAllFields =
                    fields == null || (fields.size() == 1 && Objects.equals(fields.getFirst(), "*"));
            final var sql = (selectAllFields
                            ? baseSql
                            : ("select "
                                    + customColumns(
                                            entity.mapping().jsonToDatabase(), entity.revisionProperty(), fields)
                                    + " from "
                                    + entity.table() + " "))
                    + fullWhere;
            final var names = selectAllFields
                    ? entity.mapping().databaseToJson()
                    : new TreeMap<>(
                            fields.stream().collect(toMap(entity.mapping().jsonToDatabase()::get, identity())));

            final var countAllSql = baseCountSql + where;

            final int pageValue = Math.max(page, 1);
            final int pageSizeValue = Math.max(0, Math.min(50, pageSize));
            final var whereRef = whereClause;
            final var renderersRef = renderers;

            final var result = executeInTx(
                    ctx.request(),
                    spanName,
                    Map.of("sql.find", sql, "sql.count", countAllSql),
                    transactionManager::readSQL,
                    connection -> doFindAll(
                            names,
                            connection,
                            sql,
                            countAllSql,
                            whereRef,
                            implicitWhere,
                            pageValue,
                            pageSizeValue,
                            ctx,
                            renderersRef));
            return completedFuture(result);
        };
    }

    private Map<String, Serializable> doFindAll(
            final Map<String, String> db2JsonNames,
            final Connection connection,
            final String findAllSql,
            final String countAllSql,
            final WhereClause whereRef,
            final WhereClause implicitWhere,
            final int pageValue,
            final int pageSizeValue,
            final JsonRpcMethod.Context context,
            final Map<String, Renderer> renderersRef) {
        try (final var stmt = connection.prepareStatement(findAllSql);
                final var countStmt = connection.prepareStatement(countAllSql)) {
            final var ctx = new BindingContext(context, List.of());
            if (implicitWhere != null) {
                for (final var binder : implicitWhere.binders()) {
                    binder.accept(ctx, stmt);
                    binder.accept(ctx, countStmt);
                }
            }
            if (whereRef != null) {
                for (final var binder : whereRef.binders()) {
                    binder.accept(ctx, stmt);
                    binder.accept(ctx, countStmt);
                }
            }

            // pagination
            final int index = whereRef == null ? 1 : (whereRef.binders().size() + 1);
            stmt.setInt(index, pageValue > 1 ? (pageValue - 1) * pageSizeValue : 0);
            stmt.setInt(index + 1, pageSizeValue);

            final var items = new ArrayList<Map<String, Object>>(pageSizeValue);
            try (final var rset = stmt.executeQuery()) {
                while (rset.next()) {
                    items.add(toMapResult(
                            ctx.context().request(), rset, db2JsonNames, List.of(), List.of(), renderersRef));
                }
            }

            final long total;
            try (final var rset = countStmt.executeQuery()) {
                if (!rset.next()) {
                    throw new JsonRpcException(500, "Can't count items.");
                }
                total = rset.getLong(1);
            }

            return Map.of("items", items, "total", total);
        } catch (final SQLException ex) {
            throw new JsonRpcException(500, "Can't find entities", null, ex);
        }
    }

    private Function<JsonRpcMethod.Context, CompletionStage<?>> compileUpdate(final Entity entity) {
        final var idDbNames = entity.identifiers().stream()
                .map(id -> entity.mapping().jsonToDatabase().get(id))
                .toList();
        final var updatedColumns = entity.mapping().databaseToJson().keySet().stream()
                .filter(Predicate.not(idDbNames::contains))
                .toList();
        final var implicitWhere = entity.implicitFiltering() == null
                ? null
                : prepareImplicitWhere(
                        entity.implicitFiltering().update(),
                        updatedColumns.size() /*SET*/ + entity.identifiers().size() /*where by id */ + 1);
        final var updateSql = updatedColumns.stream()
                .map(name -> name + " = ?")
                .collect(joining(
                        ", ",
                        "update " + entity.table() + " set ",
                        " where "
                                + '(' + entity.whereIds() + ")"
                                + (implicitWhere != null ? (" AND (" + implicitWhere.sql() + ')') : "")));

        final var jsonPropertiesName =
                new ArrayList<>(entity.mapping().databaseToJson().keySet());
        final var virtualFields = entity.updateVirtualFields();
        final var virtualFieldsSetters = setVirtualFields(jsonPropertiesName, virtualFields);

        final var bindingNames = new ArrayList<>(jsonPropertiesName);
        bindingNames.removeAll(idDbNames);
        bindingNames.addAll(idDbNames);

        final Function<List<Object>, List<Object>> forcedRevision = entity.revisionProperty() != null
                ? revisionValueProvider(
                        jsonPropertiesName.indexOf(entity.revisionProperty()),
                        entity.schema().properties().get(entity.revisionProperty()))
                : identity();

        final var binder =
                mergeBinders(createBinder(entity.name(), entity.schema(), bindingNames, true), implicitWhere);

        final var spanName = entity.name() + ".update";
        final var spanTags = Map.<String, Object>of("sql", updateSql);

        return ctx -> {
            doValidate(entity.validator(), ctx);

            final var values = forcedRevision.apply(
                    virtualFieldsSetters.apply(ctx, findValuesFromParams(ctx.params(), bindingNames, true)));
            final var ids = values.subList(values.size() - entity.identifiers().size(), values.size());
            if (ids.stream().anyMatch(Objects::isNull)) {
                throw new JsonRpcException(400, "Invalid identifier, ensure to set it");
            }

            final var result = requestToResult(bindingNames, values.iterator());
            executeInTx(
                    ctx.request(),
                    spanName,
                    spanTags,
                    transactionManager::writeSQL,
                    connection -> doUpdate(entity, connection, updateSql, binder, values, result, ids, ctx));
            return completedFuture(result);
        };
    }

    private Object doUpdate(
            final Entity entity,
            final Connection connection,
            final String updateSql,
            final SQLBiConsumer<BindingContext, PreparedStatement> bindAll,
            final List<Object> values,
            final Map<String, Object> result,
            final List<Object> ids,
            final JsonRpcMethod.Context context) {
        try (final var stmt = connection.prepareStatement(updateSql)) {
            bindAll.accept(new BindingContext(context, values), stmt);
            if (stmt.executeUpdate() == 0) { // should be 1
                throw new JsonRpcException(500, "Can't update entity " + values, Map.of("entity", result), null);
            }
        } catch (final SQLException ex) {
            throw new JsonRpcException(500, "Can't update entity " + entity.name() + ' ' + ids, null, ex);
        }
        return null;
    }

    private Function<JsonRpcMethod.Context, CompletionStage<?>> compileCreate(final Entity entity) {
        final var jsonPropertiesName =
                new ArrayList<>(entity.mapping().databaseToJson().keySet());
        final var insertSql = entity.mapping().databaseToJson().keySet().stream()
                        .collect(joining(", ", "insert into " + entity.table() + " (", ")"))
                + entity.mapping().databaseToJson().keySet().stream()
                        .map(i -> "?")
                        .collect(joining(", ", " values (", ")"));
        final var bindAll = createBinder(entity.name(), entity.schema(), jsonPropertiesName, true);
        final SQLFunction<Connection, PreparedStatement> statementFactory = entity.autoGeneratedIds()
                ? connection -> connection.prepareStatement(insertSql, PreparedStatement.RETURN_GENERATED_KEYS)
                : connection -> connection.prepareStatement(insertSql);
        final SQLBiConsumer<PreparedStatement, Map<String, Object>> postExecute = entity.autoGeneratedIds()
                ? (ps, data) -> {
                    try (final var keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) {
                            logger.severe(() -> "No generated key available after '" + insertSql + "' statement");
                            throw new JsonRpcException(500, "Entity creation didn't populated identifiers.");
                        }

                        final var keyIt = entity.identifiers().iterator();
                        int index = 1;
                        do {
                            data.put(keyIt.next(), keys.getObject(index++));
                        } while (keys.next());
                    } catch (final SQLException e) {
                        throw new JsonRpcException(500, "Entity creation identifier failed to be retrieved.", null, e);
                    }
                }
                : (ps, data) -> {};

        final var virtualFields = entity.createVirtualFields();
        final var virtualFieldsSetters = setVirtualFields(jsonPropertiesName, virtualFields);

        final Function<List<Object>, List<Object>> forcedRevision = entity.revisionProperty() != null
                ? revisionValueProvider(
                        jsonPropertiesName.indexOf(entity.revisionProperty()),
                        entity.schema().properties().get(entity.revisionProperty()))
                : identity();

        final var spanName = entity.name() + ".create";
        final var spanTags = Map.<String, Object>of("sql", insertSql);

        return ctx -> {
            doValidate(entity.validator(), ctx);

            final var values = forcedRevision.apply(
                    virtualFieldsSetters.apply(ctx, findValuesFromParams(ctx.params(), jsonPropertiesName, true)));
            final var result = requestToResult(jsonPropertiesName, values.iterator());
            executeInTx(
                    ctx.request(),
                    spanName,
                    spanTags,
                    transactionManager::writeSQL,
                    connection -> doCreate(connection, statementFactory, bindAll, values, result, postExecute, ctx));
            return completedFuture(result);
        };
    }

    private Object doCreate(
            final Connection connection,
            final SQLFunction<Connection, PreparedStatement> statementFactory,
            final SQLBiConsumer<BindingContext, PreparedStatement> bindAll,
            final List<Object> values,
            final Map<String, Object> result,
            final SQLBiConsumer<PreparedStatement, Map<String, Object>> postExecute,
            final JsonRpcMethod.Context context) {
        try (final var stmt = statementFactory.apply(connection)) {
            bindAll.accept(new BindingContext(context, values), stmt);
            if (stmt.executeUpdate() == 0) {
                throw new JsonRpcException(500, "Can't create entity " + values, Map.of("entity", result), null);
            }
            postExecute.accept(stmt, result);
        } catch (final SQLException ex) {
            throw new JsonRpcException(500, "Can't create entity", null, ex);
        }
        return null;
    }

    // todo: when revisionned do we want to create a deleted flag instead? can be done by the client using update to
    // delete
    //       + right permission setup so maybe not needed
    private Function<JsonRpcMethod.Context, CompletionStage<?>> compileDeleteById(final Entity entity) {
        final var identifiers = entity.identifiers();
        final var implicitWhere = entity.implicitFiltering() == null
                ? null
                : prepareImplicitWhere(entity.implicitFiltering().delete(), 1);
        final var binder = mergeBinders(entity.bindIdsNotNullable(), implicitWhere);
        final var deleteById = "delete  from " + entity.table() + " where "
                + '(' + entity.whereIds() + ")"
                + (implicitWhere != null ? (" AND (" + implicitWhere.sql() + ')') : "");

        final var spanName = entity.name() + ".deleteById";
        final var spanTags = Map.<String, Object>of("sql", deleteById);

        return ctx -> {
            final var ids = findValuesFromParams(ctx.params(), identifiers, false);
            executeInTx(
                    ctx.request(),
                    spanName,
                    spanTags,
                    transactionManager::writeSQL,
                    connection -> doDeleteById(binder, connection, deleteById, ids, ctx));
            return completedFuture(Map.of("success", true));
        };
    }

    private Object doDeleteById(
            final SQLBiConsumer<BindingContext, PreparedStatement> bindIds,
            final Connection connection,
            final String deleteById,
            final List<Object> ids,
            final JsonRpcMethod.Context context) {
        try (final var stmt = connection.prepareStatement(deleteById)) {
            bindIds.accept(new BindingContext(context, ids), stmt);
            if (stmt.executeUpdate() == 0) {
                throw new JsonRpcException(
                        404,
                        "Can't delete entity with id=" + ids,
                        Map.of("id", ids.size() == 1 ? ids.getFirst() : ids),
                        null);
            }
        } catch (final SQLException ex) {
            throw new JsonRpcException(500, "Can't delete entity", null, ex);
        }
        return null;
    }

    private Function<JsonRpcMethod.Context, CompletionStage<?>> compileFindById(final Entity entity) {
        final var identifiers = entity.identifiers();

        final var projectionNames = new TreeMap<>(entity.mapping().databaseToJson().entrySet().stream()
                .filter(Predicate.not(i -> identifiers.contains(i.getValue())))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)));
        final var columns = projectionNames.entrySet().stream()
                .map(name -> selectColumn(entity.revisionProperty(), name))
                .collect(joining(", "));
        final var implicitWhere = entity.implicitFiltering() == null
                ? null
                : prepareImplicitWhere(entity.implicitFiltering().view(), identifiers.size() + 1);
        final var sqlEnd = " from "
                + entity.table() + " where "
                + '(' + entity.whereIds() + ")"
                + (implicitWhere != null ? (" AND (" + implicitWhere.sql() + ')') : "")
                + (entity.revisionProperty() != null
                        ? identifiers.stream()
                                .map(k -> entity.mapping().jsonToDatabase().get(k))
                                .collect(joining(", ", " group by ", ""))
                        : "");
        final var findByIdSql = "select " + columns + sqlEnd;
        final var binder = mergeBinders(entity.bindIdsNotNullable(), implicitWhere);

        final var spanName = entity.name() + ".findById";
        final var spanTags = Map.<String, Object>of("sql", findByIdSql);

        return ctx -> {
            final var ids = findValuesFromParams(ctx.params(), identifiers, false);

            final Map<String, Renderer> renderers;
            if (ctx.params() instanceof Map<?, ?> map && map.get("renderers") instanceof Map<?, ?> r) {
                renderers = toRenderers(r);
            } else {
                renderers = Map.of();
            }

            final List<String> fields;
            if (ctx.params() instanceof Map<?, ?> map && map.get("fields") instanceof List<?> f) {
                @SuppressWarnings("unchecked")
                final var value = (List<String>) f;
                fields = value;
            } else {
                fields = null;
            }

            final var selectAllFields =
                    fields == null || (fields.size() == 1 && Objects.equals(fields.getFirst(), "*"));
            final var sql = selectAllFields
                    ? findByIdSql
                    : ("select " + customColumns(entity.mapping().jsonToDatabase(), entity.revisionProperty(), fields)
                            + sqlEnd);
            final var names = selectAllFields
                    ? projectionNames
                    : new TreeMap<>(
                            fields.stream().collect(toMap(entity.mapping().jsonToDatabase()::get, identity())));

            final var result = executeInTx(
                    ctx.request(),
                    spanName,
                    spanTags,
                    transactionManager::readSQL,
                    connection -> doFindById(
                            binder, ctx.request(), connection, sql, ids, names, identifiers, ctx, renderers));
            return completedFuture(result);
        };
    }

    private Map<String, Object> doFindById(
            final SQLBiConsumer<BindingContext, PreparedStatement> bindIds,
            final Request request,
            final Connection connection,
            final String findByIdSql,
            final List<Object> ids,
            final TreeMap<String, String> projectionNames,
            final List<String> identifiers,
            final JsonRpcMethod.Context context,
            final Map<String, Renderer> renderers) {
        try (final var stmt = connection.prepareStatement(findByIdSql)) {
            bindIds.accept(new BindingContext(context, ids), stmt);
            try (final var rset = stmt.executeQuery()) {
                if (!rset.next()) {
                    throw new JsonRpcException(
                            404, "Entity not found", Map.of("id", ids.size() == 1 ? ids.getFirst() : ids), null);
                }

                final var res = toMapResult(request, rset, projectionNames, identifiers, ids, renderers);
                if (rset.next()) {
                    throw new JsonRpcException(
                            405, "Ambiguous entity", Map.of("id", ids.size() == 1 ? ids.getFirst() : ids), null);
                }
                return res;
            }
        } catch (final SQLException ex) {
            throw new JsonRpcException(500, "Can't find entity", null, ex);
        }
    }

    private BiFunction<JsonRpcMethod.Context, List<Object>, List<Object>> setVirtualFields(
            final List<String> jsonPropertiesName, final Map<String, Model.GenerationType> virtualFields) {
        return virtualFields != null
                ? virtualFields.entrySet().stream()
                        .map(e -> switch (e.getValue()) {
                            case uuid -> setUuid(jsonPropertiesName.indexOf(e.getKey()));
                            case datetime -> setDateTime(jsonPropertiesName.indexOf(e.getKey()));
                            case sub -> setSub(jsonPropertiesName.indexOf(e.getKey()));
                        })
                        .reduce((a, b) -> (c, v) -> { // chain
                            var out = a.apply(c, v);
                            out = b.apply(c, out);
                            return out;
                        })
                        .orElseThrow()
                : (c, v) -> v;
    }

    private BiFunction<JsonRpcMethod.Context, List<Object>, List<Object>> setSub(final int idx) {
        return (context, values) -> {
            final var copy = editableOrNew(values);
            final var jwt = context.request().attribute(Jwt.class.getName(), Jwt.class);
            copy.set(
                    idx,
                    jwt == null
                            ? null
                            : jwt.claim("sub", String.class)
                                    .orElseThrow(() -> new JsonRpcException(403, "Unauthenticated call.")));
            return copy;
        };
    }

    private BiFunction<JsonRpcMethod.Context, List<Object>, List<Object>> setDateTime(final int idx) {
        return (context, values) -> {
            final var copy = editableOrNew(values);
            copy.set(idx, OffsetDateTime.now());
            return copy;
        };
    }

    private BiFunction<JsonRpcMethod.Context, List<Object>, List<Object>> setUuid(final int idx) {
        return (context, values) -> {
            final var copy = editableOrNew(values);
            copy.set(idx, UUID.randomUUID().toString());
            return copy;
        };
    }

    private List<Object> editableOrNew(final List<Object> values) {
        return values instanceof ArrayList ? values : new ArrayList<>(values);
    }

    private String toOrderByClause(final Map<?, ?> sort, final Map<String, String> json2Db) {
        if (sort == null) {
            return "";
        }

        @SuppressWarnings("unchecked")
        final var casted = (Map<String, String>) sort;

        final var property = casted.get("name");
        if (property == null) {
            return "";
        }

        final var dbName = json2Db.get(property);
        if (dbName == null) {
            logger.warning(() -> "Can't sort by '" + property + "', ignoring");
            return "";
        }

        final var direction = casted.getOrDefault("direction", "ASC").toUpperCase(ROOT);
        return " order by " + dbName + " "
                + switch (direction) {
                    case "ASC", "DESC" -> direction;
                    default -> throw new JsonRpcException(400, "Invalid sort direction, ensure to use ASC or DESC");
                };
    }

    private String toWhereIds(final List<String> identifiers, final Map<String, String> jsonToDatabase) {
        return identifiers.stream()
                .map(jsonToDatabase::get)
                .map(k -> k + " = ?")
                .collect(joining(" AND "));
    }

    private WhereClause toWhereClause(
            final Map<?, ?> filters,
            final Map<String, String> json2Db,
            final Set<String> allowedWhereOperators,
            final int startIndex,
            final String prefix) {
        if (filters.isEmpty()) {
            return null;
        }

        @SuppressWarnings("unchecked")
        final var casted = (Map<String, Map<String, Object>>) filters;

        final var bindings = new ArrayList<SQLBiConsumer<BindingContext, PreparedStatement>>();
        final var index = new AtomicInteger(startIndex);
        return new WhereClause(
                casted.entrySet().stream()
                        .filter(it -> {
                            if (!json2Db.containsKey(it.getKey())) {
                                logger.warning(() -> "'" + it.getKey() + "' is not filterable, ignoring");
                                return false;
                            }
                            return true;
                        })
                        .map(e -> {
                            final var value = e.getValue().get("value");
                            if (value == null) {
                                throw new JsonRpcException(400, "No value for filter " + e);
                            }

                            final int idx = index.getAndIncrement();
                            bindings.add((ctx, s) -> s.setObject(idx, value));
                            return json2Db.get(e.getKey()) + ' '
                                    + toWhereOperator(e.getValue().get("operator"), allowedWhereOperators) + " ?";
                        })
                        .collect(joining(" AND ", " " + prefix + " (", ")")),
                bindings);
    }

    private String toWhereOperator(final Object operator, final Set<String> allowed) {
        if (operator == null) {
            return "=";
        }

        final var value = operator.toString();
        final var lowerCase = value.toLowerCase(ROOT);
        if (!allowed.contains(lowerCase)) {
            throw new JsonRpcException(400, "Invalid operator: '" + operator + "'");
        }
        return lowerCase;
    }

    // key/value, key=json name, value = renderer name
    public Map<String, Renderer> toRenderers(final Map<?, ?> renderers) {
        if (renderers == null) {
            return Map.of();
        }

        final var casted = asGenericObject(renderers);
        return casted.entrySet().stream().filter(e -> e.getValue() != null).collect(toMap(Map.Entry::getKey, e -> {
            final var impl = this.renderers.get(e.getValue().toString());
            if (impl == null) {
                throw new JsonRpcException(406, "Invalid renderer: '" + e.getValue() + "'", Map.ofEntries(e), null);
            }
            return impl;
        }));
    }

    private <T> T executeInTx(
            final Request request,
            final String name,
            final Map<String, Object> customTags,
            final Function<SQLFunction<Connection, T>, T> txFactory,
            final Function<Connection, T> impl) {
        final var connection = findConnection(request);
        return spans.wrap(
                request,
                name,
                customTags,
                () -> connection == null ? txFactory.apply(impl::apply) : impl.apply(connection));
    }

    private Function<List<Object>, List<Object>> revisionValueProvider(
            final int index, final Model.JsonSchema jsonSchema) {
        if (jsonSchema.type().contains(string) && jsonSchema.format() == date_time) {
            return values -> {
                final var copy = new ArrayList<>(values);
                copy.set(index, OffsetDateTime.now());
                return copy;
            };
        }
        if (jsonSchema.type().contains(number)) {
            return values -> {
                final var copy = new ArrayList<>(values);
                copy.set(index, Instant.now().toEpochMilli());
                return copy;
            };
        }
        throw new IllegalArgumentException("Unknown revision type: " + jsonSchema);
    }

    private Map<String, Object> requestToResult(
            final List<String> jsonPropertiesName, final Iterator<Object> valuesIt) {
        return jsonPropertiesName.stream()
                .map(key -> {
                    final var value = valuesIt.next();
                    if (value == null) {
                        return null;
                    }
                    return entry(key, value);
                })
                .filter(Objects::nonNull)
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private List<Object> findValuesFromParams(
            final Object params, final SequencedCollection<String> expectedKeys, final boolean allowNull) {
        final var entries = findEntries(params, expectedKeys, allowNull);
        if (entries.size() != expectedKeys.size()) {
            throw new JsonRpcException(400, "Invalid request, ensure to format the request properly: " + expectedKeys);
        }
        return entries;
    }

    private SQLBiConsumer<BindingContext, PreparedStatement> mergeBinders(
            final SQLBiConsumer<BindingContext, PreparedStatement> bindIds, final WhereClause implicitWhere) {
        return implicitWhere == null || implicitWhere.binders().isEmpty()
                ? bindIds
                : (values, stmt) -> {
                    bindIds.accept(values, stmt);
                    for (final var bind : implicitWhere.binders()) {
                        bind.accept(values, stmt);
                    }
                };
    }

    private SQLBiConsumer<BindingContext, PreparedStatement> createBinder(
            final String entityName,
            final Model.JsonSchema schema,
            final Collection<String> identifiers,
            final boolean nullable) {
        final var index = new AtomicInteger();
        return flattenBinders(identifiers.stream()
                .map(id -> toBinder(entityName, schema, id, index.getAndIncrement(), nullable))
                .toList());
    }

    private SQLBiConsumer<BindingContext, PreparedStatement> toBinder(
            final String entityName,
            final Model.JsonSchema schema,
            final String property,
            final int index,
            final boolean nullable) {
        final var model = schema.properties().get(property);
        if (model == null) {
            throw new IllegalStateException("Missing property '" + property + "' in '" + entityName + "'");
        }

        final int bindingIndex = index + 1;
        final SQLConsumer<PreparedStatement> nullBinder =
                nullable && model.type().contains(nullValue)
                        ? findPropertyNullBinder(entityName, property, model, bindingIndex)
                        : s -> {
                            throw new IllegalStateException("Property '" + property + "' can't be null");
                        };
        final Function<Object, Object> valueMapper =
                model.type().contains(string) ? findStringPropertyMapper(model) : identity();

        return (values, stmt) -> {
            final var value = values.values().get(index);
            if (value == null) {
                nullBinder.accept(stmt);
            }
            stmt.setObject(bindingIndex, valueMapper.apply(value));
        };
    }

    private SQLConsumer<PreparedStatement> findPropertyNullBinder(
            final String entityName, final String property, final Model.JsonSchema model, final int bindingIndex) {
        if (model.type() == null) {
            throw new IllegalArgumentException("Invalid property schema in '" + entityName + "#" + property + "'");
        }

        return switch (model.type().stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(string)) {
            case string -> model.format() == null
                    ? ps -> ps.setNull(bindingIndex, Types.VARCHAR)
                    : switch (model.format()) {
                        case uri, ipv4, ipv6, email, hostname, json_pointer, uuid, regex, duration -> ps ->
                                ps.setNull(bindingIndex, Types.VARCHAR);
                        case date -> ps -> ps.setNull(bindingIndex, Types.DATE);
                        case time -> ps -> ps.setNull(bindingIndex, Types.TIME);
                        case date_time -> ps -> ps.setNull(bindingIndex, Types.TIMESTAMP);
                    };
            case bool -> ps -> ps.setNull(bindingIndex, Types.BOOLEAN);
            case number -> ps -> ps.setNull(bindingIndex, Types.NUMERIC);
            case integer -> ps -> ps.setNull(bindingIndex, Types.INTEGER);
            default -> throw new IllegalArgumentException("Unsupported property (can't create a null binder)");
        };
    }

    private Function<Object, Object> findStringPropertyMapper(final Model.JsonSchema model) {
        return model.format() == null
                ? identity()
                : switch (model.format()) {
                    case uri, ipv4, ipv6, email, hostname, json_pointer, uuid, regex, duration -> identity();
                    case date -> v -> LocalDate.parse(String.valueOf(v));
                    case time -> v -> LocalTime.parse(String.valueOf(v));
                    case date_time -> v -> OffsetDateTime.parse(String.valueOf(v));
                };
    }

    private String customColumns(
            final Map<String, String> json2DbNames, final String dbRevisionColumn, final List<String> fields) {
        return fields.stream()
                .map(f -> {
                    final var dbName = json2DbNames.get(f);
                    if (dbName == null) {
                        throw new JsonRpcException(400, "Invalid fields filter", Map.of("field", f), null);
                    }
                    return selectColumn(dbRevisionColumn, entry(dbName, f));
                })
                .collect(joining(", "));
    }

    private Map<String, Object> toMapResult(
            final Request request,
            final ResultSet rset,
            final Map<String, String> projectionMapping,
            final List<String> identifierKeys,
            final List<Object> ids,
            final Map<String, Renderer> renderers) {
        final var res = projectionMapping.entrySet().stream()
                .flatMap(it -> {
                    try {
                        final var object = rset.getObject(it.getKey());
                        if (object == null) {
                            return Stream.empty();
                        }

                        final var renderer = renderers.get(it.getValue());
                        return Stream.of(
                                entry(it.getValue(), renderer == null ? object : renderer.render(request, object)));
                    } catch (final SQLException e) {
                        throw new PersistenceException(e);
                    }
                })
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (!identifierKeys.isEmpty()) { // inject ids since they were not in the projection
            final var idIt = ids.iterator();
            res.putAll(identifierKeys.stream().collect(toMap(identity(), k -> idIt.next())));
        }

        return res;
    }

    private SQLBiConsumer<BindingContext, PreparedStatement> flattenBinders(
            final List<SQLBiConsumer<BindingContext, PreparedStatement>> binders) {
        return binders.size() == 1
                ? binders.getFirst()
                : binders.stream()
                        .reduce(SQLBiConsumer::andThen)
                        // unlikely it fails there since we validated it
                        .orElseThrow(() -> new IllegalStateException("No id"));
    }

    @SuppressWarnings("unchecked")
    private List<Object> findEntries(
            final Object params, final SequencedCollection<String> keys, final boolean allowNull) {
        if (params instanceof List<?> list) {
            if (list.size() < keys.size()) { // here null is only allowed if explicitly set
                logger.severe(() -> "Invalid request: " + params + ", expected=" + keys);
                throw new JsonRpcException(400, "Invalid request, expected: " + keys);
            }
            return (List<Object>) list;
        }

        if (params instanceof Map<?, ?> map) {
            return keys.stream()
                    .map(k -> {
                        final var value = map.get(k);
                        if (!allowNull && value == null) {
                            throw new JsonRpcException(
                                    400, "Invalid request, expected: " + keys + ", missing '" + k + "'");
                        }
                        return value;
                    })
                    .toList();
        }

        logger.severe(() -> "Invalid request: " + params + ", expected=" + keys);
        throw new JsonRpcException(400, "Unexpected request: " + params);
    }

    private Entity toEntity(final Model.EntitySpec spec) {
        final var jsonNames = spec.jsonSchema().properties().keySet();
        final Function<String, String> nameMapping = spec.naming() == null
                ? identity()
                : switch (spec.naming()) {
                    case CAMEL_TO_SNAKE -> nameMapper::camelToSnake;
                };

        final var json2DbNames = jsonNames.stream().collect(toMap(identity(), nameMapping, (a, b) -> a, TreeMap::new));

        final var schema = new Model.JsonSchema(
                List.of(object),
                // recreate the schema to sort props
                new TreeMap<>(spec.jsonSchema().properties()),
                null,
                spec.jsonSchema().required(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        final var identifiers =
                spec.identifierNames() == null && spec.jsonSchema().properties().containsKey("id")
                        ? List.of("id")
                        : spec.identifierNames();

        return new Entity(
                spec.name(),
                spec.tableName(),
                spec.generatedCreateFields(),
                spec.generatedUpdateFields(),
                spec.autoGeneratedIds(),
                identifiers,
                spec.allowedSortKeys() == null ? Set.of() : new HashSet<>(spec.allowedSortKeys()),
                spec.allowedWhereKeys() == null ? Set.of() : new HashSet<>(spec.allowedWhereKeys()),
                spec.allowedWhereOperators() == null
                        ? Set.of("=")
                        : (spec.allowedWhereOperators().contains("default")
                                ? defaultWhereOperators
                                : new HashSet<>(spec.allowedWhereOperators())),
                spec.revisionProperty(),
                schema,
                new Entity.NameMapping(
                        json2DbNames,
                        json2DbNames.entrySet().stream()
                                .collect(toMap(Map.Entry::getValue, Map.Entry::getKey, (a, b) -> a, TreeMap::new))),
                spec.implicitFiltering(),
                spec.validateWithJsonSchema()
                        ? validatorFactory.newInstance(asGenericObject(schema))
                        : o -> validationOk,
                toWhereIds(identifiers, json2DbNames),
                createBinder(spec.name(), schema, identifiers, false),
                createBinder(spec.name(), schema, identifiers, true));
    }

    private void validateEntity(final Model.EntitySpec spec) { // todo: aggregate the errors in one
        if (spec.name() == null || spec.name().isBlank()) {
            throw new IllegalArgumentException("No entity name for " + spec);
        }
        if ("hcms".equals(spec.name())) { // reserved
            throw new IllegalArgumentException("Invalid entity name for " + spec);
        }

        if (spec.tableName() == null || spec.tableName().isBlank()) {
            throw new IllegalArgumentException("No table name for " + spec);
        }

        if (spec.jsonSchema() == null
                || spec.jsonSchema().type() == null
                || !spec.jsonSchema().type().contains(object)
                || spec.jsonSchema().properties().isEmpty()) {
            throw new IllegalArgumentException(
                    "An entity must be of type `object` and have at least one property: '" + spec.name() + "'");
        }
        if ((spec.identifierNames() == null || spec.identifierNames().isEmpty())
                && !spec.jsonSchema().properties().containsKey("id")) {
            throw new IllegalArgumentException("No identifier field set: " + spec);
        }

        final var invalidVirtualFields = Stream.of(spec.generatedCreateFields(), spec.generatedUpdateFields())
                .filter(Objects::nonNull)
                .flatMap(m -> m.entrySet().stream())
                .filter(e -> !spec.jsonSchema().properties().containsKey(e.getKey()))
                .map(Map.Entry::getKey)
                .distinct()
                .toList();
        if (!invalidVirtualFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid virtual fields, check generatedCreateFields and generatedUpdateFields for '" + spec.name()
                            + "': " + invalidVirtualFields);
        }

        final var errorneousProperties = spec.jsonSchema().properties().entrySet().stream()
                .flatMap(e -> validateEntityProperty(e.getValue()))
                .toList();
        if (!errorneousProperties.isEmpty()) {
            throw new IllegalArgumentException("Some properties are invalid in entity '" + spec.name() + "':\n"
                    + String.join("\n", errorneousProperties));
        }

        final var revisionField = spec.revisionProperty();
        if (revisionField != null) {
            final var prop = spec.jsonSchema().properties().get(revisionField);
            if (prop == null) {
                throw new IllegalArgumentException(
                        "Property not found: '" + revisionField + "' in entity '" + spec.name() + "'");
            }

            if (prop.type() == null
                    || !((prop.type().contains(string) && prop.format() == date_time)
                            || prop.type().contains(number))) {
                throw new IllegalArgumentException("Property should be of type `number` of `date-time`: '"
                        + revisionField + "' in entity '" + spec.name() + "'");
            }
        }
    }

    private String selectColumn(final String dbRevisionColumn, final Map.Entry<String, String> name) {
        return (Objects.equals(dbRevisionColumn, name.getKey()) ? "max(" + name.getKey() + ")" : name.getKey()) + " as "
                + name.getValue();
    }

    private Stream<String> validateEntityProperty(final Model.JsonSchema prop) {
        return switch (prop.type() == null
                ? nullValue
                : prop.type().stream().filter(Objects::nonNull).findFirst().orElse(nullValue)) {
            case nullValue, object, array -> Stream.of("Invalid property type, can't be '" + prop.type() + "'");
            default -> Stream.empty();
        };
    }

    private void doValidate(final Function<Object, ValidationResult> validator, final JsonRpcMethod.Context ctx) {
        final var errors = ctx.params() instanceof Map<?, ?> map
                ? validator.apply(map)
                : new ValidationResult(List.of(
                        new ValidationResult.ValidationError("", "Invalid request, it must use named parameters")));
        if (!errors.isSuccess()) {
            throw new JsonRpcException(
                    400,
                    "Invalid request",
                    Map.of(
                            "errors",
                            errors.errors().stream()
                                    .map(e -> Map.of("field", e.field(), "message", e.message()))
                                    .toList()),
                    null);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asGenericObject(final Object entity) {
        return (Map<String, Object>) jsonMapper.fromString(Object.class, jsonMapper.toString(entity));
    }

    private static class ModelJsonRpcMethod extends DefaultJsonRpcMethod {
        private ModelJsonRpcMethod(final String name, final Function<JsonRpcMethod.Context, CompletionStage<?>> impl) {
            super(1_000, name, impl);
        }

        @Override
        public String toString() {
            return name();
        }
    }

    private record WhereClause(String sql, List<SQLBiConsumer<BindingContext, PreparedStatement>> binders) {}

    record BindingContext(JsonRpcMethod.Context context, List<Object> values) {}
}
