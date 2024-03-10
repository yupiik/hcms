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
package io.yupiik.hcms.jsonrpc;

import static java.util.Map.entry;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.persistence.api.TransactionManager;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.hcms.test.HCMSSupport;
import io.yupiik.hcms.test.SimpleJsonRpcClient;
import io.yupiik.hcms.test.SimpleJwts;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

@HCMSSupport
class VirtualJsonRpcMethodTest {
    @Test
    void findByIdOk(@Fusion final SimpleJsonRpcClient client) {
        final var result = client.post(null, "posts.findById", Map.of("id", "00001"));
        assertJsonRpcResultOk(result);
        assertEquals(Map.of("id", "00001", "title", "First post", "content", "HCMS rocks."), result.as(Map.class));
    }

    @Test
    void findByIdFilteringFields(@Fusion final SimpleJsonRpcClient client) {
        final var result = client.post(null, "posts.findById", Map.of("id", "00001", "fields", List.of("id", "title")));
        assertJsonRpcResultOk(result);
        assertEquals(Map.of("id", "00001", "title", "First post"), result.as(Map.class));
    }

    @Test
    void findByIdKo(@Fusion final SimpleJsonRpcClient client) {
        final var result = client.post(null, "posts.findById", Map.of("id", "xxxx"));
        assertFalse(result.isOk());
        assertEquals(
                Map.of("code", BigDecimal.valueOf(404), "message", "Entity not found", "data", Map.of("id", "xxxx")),
                result.as(Map.class));
    }

    @Test
    void deleteByIdOk(
            final TestInfo info, @Fusion final SimpleJsonRpcClient client, @Fusion final TransactionManager tx) {
        final var id = insertFakeData(tx, info.getTestMethod().orElseThrow().getName());
        assertTrue(exists(tx, id));
        final var result = client.post(null, "posts.deleteById", Map.of("id", id));
        assertJsonRpcResultOk(result);
        assertEquals(Map.of("success", true), result.as(Map.class));
        assertFalse(exists(tx, id));
    }

    @Test
    void deleteByIdKo(@Fusion final SimpleJsonRpcClient client, @Fusion final TransactionManager tx) {
        assertFalse(exists(tx, "missing"));
        final var result = client.post(null, "posts.deleteById", Map.of("id", "missing"));
        assertFalse(result.isOk());
        assertEquals(
                Map.of(
                        "code",
                        BigDecimal.valueOf(404),
                        "message",
                        "Can't delete entity with id=[missing]",
                        "data",
                        Map.of("id", "missing")),
                result.as(Map.class));
    }

    @Test
    void create(@Fusion final SimpleJsonRpcClient client) {
        final var result = client.post(
                null,
                "posts.create",
                Map.of(
                        "title", "A good post",
                        "content", "HCMS enables to ease that work."));
        assertJsonRpcResultOk(result);

        final var id = result.as(Map.class).get("id");
        assertNotNull(id);

        // check a findById
        assertEquals(
                Map.of(
                        "id", id,
                        "title", "A good post",
                        "content", "HCMS enables to ease that work."),
                client.post(null, "posts.findById", Map.of("id", id)).as(Map.class));

        // just cleanup
        assertJsonRpcResultOk(client.post(null, "posts.deleteById", Map.of("id", id)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void asciidocRenderer(@Fusion final SimpleJsonRpcClient client) {
        final var result = client.post(
                null,
                "posts.create",
                Map.of(
                        "title", "A good post",
                        "content", "= First post\n\nThis is a post.\n"));
        assertJsonRpcResultOk(result);

        final var id = result.as(Map.class).get("id");
        assertNotNull(id);

        // check a findById
        final var renderedPost = Map.of(
                "id",
                id,
                "title",
                "A good post",
                "content",
                """
                         <div class="paragraph">
                         <p>
                        This is a post.
                         </p>
                         </div>
                         """);
        assertEquals(
                renderedPost,
                client.post(null, "posts.findById", Map.of("id", id, "renderers", Map.of("content", "adoc")))
                        .as(Map.class));

        assertTrue(((List<Map<String, Object>>)
                        client.post(null, "posts.findAll", Map.of("renderers", Map.of("content", "adoc")))
                                .as(Map.class)
                                .get("items"))
                .contains(renderedPost));

        // just cleanup
        assertJsonRpcResultOk(client.post(null, "posts.deleteById", Map.of("id", id)));
    }

    @Test
    void createValidated(@Fusion final SimpleJsonRpcClient client) {
        final var result = client.post(
                null,
                "posts-validated.create",
                // missing title
                Map.of("content", "HCMS enables to ease that work."));
        assertFalse(result.isOk());
        assertEquals(
                Map.of(
                        "code",
                        BigDecimal.valueOf(400),
                        "message",
                        "Invalid request",
                        "data",
                        Map.of(
                                "errors",
                                List.of(Map.of("field", "/", "message", "title is required and is not present")))),
                result.as(Map.class));
    }

    @Test
    void filtered(@Fusion final SimpleJsonRpcClient client, @Fusion final SimpleJwts jwts) {
        final var jwt = jwts.forUser("test@app.com");
        final var result = client.post(
                jwt,
                "posts-filtered.create",
                Map.of(
                        "title", "A good post",
                        "status", "DRAFT",
                        "content", "HCMS enables to ease that work."));
        assertJsonRpcResultOk(result);

        final var createdPost = result.as(Map.class);
        final var id = createdPost.get("id");
        assertNotNull(id);

        // check a find endpoints - empty when anonymous
        assertEquals(
                Map.of("total", BigDecimal.ZERO, "items", List.of()),
                client.post(null, "posts-filtered.findAll", Map.of()).as(Map.class));
        assertEquals(
                BigDecimal.valueOf(404),
                client.post(null, "posts-filtered.findById", Map.of("id", id))
                        .as(Map.class)
                        .get("code"));

        // check a find endpoints - filled when using the right author
        assertEquals(
                Map.of("total", BigDecimal.ONE, "items", List.of(createdPost)),
                client.post(jwt, "posts-filtered.findAll", Map.of()).as(Map.class));
        assertEquals(
                createdPost,
                client.post(jwt, "posts-filtered.findById", Map.of("id", id)).as(Map.class));

        // just cleanup
        assertJsonRpcResultOk(client.post(null, "posts-filtered.deleteById", Map.of("id", id)));
    }

    @Test
    void findAll(@Fusion final SimpleJsonRpcClient client) {
        // + the initial one created with the SQL script
        final var posts = IntStream.range(0, 2)
                .mapToObj(i -> client.post(
                        null,
                        "posts.create",
                        Map.of(
                                "title", "A good post #" + i,
                                "content", "POST " + i)))
                .peek(r -> assertTrue(r.isOk(), r::debug))
                .toList();

        // hack to ignore ids but check it is there since they are dynamic
        @SuppressWarnings("unchecked")
        final IntFunction<Map<String, Object>> fetchPage = page -> {
            final var res = client.post(
                    null,
                    "posts.findAll",
                    Map.of(
                            "page",
                            page,
                            "pageSize",
                            2,
                            "sortBy",
                            Map.of(
                                    "name", "title",
                                    "direction", "ASC")));
            assertTrue(res.isOk(), res::debug);
            return ((Map<String, Object>) res.as(Map.class))
                    .entrySet().stream()
                            .map(e -> "items".equals(e.getKey())
                                    ? entry(
                                            "items",
                                            ((List<Map<String, Object>>) e.getValue())
                                                    .stream()
                                                            .map(m -> m.entrySet().stream()
                                                                    .filter(i -> !"id".equals(i.getKey()))
                                                                    .collect(toMap(
                                                                            Map.Entry::getKey, Map.Entry::getValue)))
                                                            .toList())
                                    : e)
                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
        };

        // findAll first page
        assertEquals(
                Map.of(
                        "total",
                        BigDecimal.valueOf(3),
                        "items",
                        List.of(
                                Map.of(
                                        "title", "A good post #0",
                                        "content", "POST 0"),
                                Map.of(
                                        "title", "A good post #1",
                                        "content", "POST 1"))),
                fetchPage.apply(1));
        assertEquals(
                Map.of(
                        "total",
                        BigDecimal.valueOf(3),
                        "items",
                        List.of(Map.of(
                                "title", "First post",
                                "content", "HCMS rocks."))),
                fetchPage.apply(2));

        // filtering by fields
        assertJsonRpcResult(
                Map.of(
                        "total",
                        BigDecimal.valueOf(3),
                        "items",
                        List.of(
                                Map.of("id", posts.get(0).as(Map.class).get("id"), "title", "A good post #0"),
                                Map.of("id", posts.get(1).as(Map.class).get("id"), "title", "A good post #1"))),
                client.post(
                        null,
                        "posts.findAll",
                        Map.of(
                                "page",
                                1,
                                "pageSize",
                                2,
                                "sortBy",
                                Map.of(
                                        "name", "title",
                                        "direction", "ASC"),
                                "fields",
                                List.of("id", "title"))));

        // just cleanup temp posts
        posts.forEach(r -> assertTrue(client.post(
                        null, "posts.deleteById", Map.of("id", r.as(Map.class).get("id")))
                .isOk()));
    }

    @Test
    void findAllWhere(@Fusion final SimpleJsonRpcClient client) {
        {
            final var res = client.post(
                    null,
                    "posts.findAll",
                    Map.of("filters", Map.of("title", Map.of("operator", "like", "value", "First%"))));
            assertTrue(res.isOk(), res::debug);
            assertEquals(BigDecimal.ONE, res.as(Map.class).get("total"));
        }
        {
            final var res = client.post(
                    null,
                    "posts.findAll",
                    Map.of("filters", Map.of("title", Map.of("operator", "=", "value", "unmatched"))));
            assertTrue(res.isOk(), res::debug);
            assertEquals(BigDecimal.ZERO, res.as(Map.class).get("total"));
        }
    }

    @Test
    void update(@Fusion final SimpleJsonRpcClient client) {
        final var init = client.post(
                null,
                "posts.create",
                Map.of(
                        "title", "A good post",
                        "content", "HCMS enables to ease that work."));
        assertJsonRpcResultOk(init);

        final var id = init.as(Map.class).get("id");
        assertNotNull(id);

        // update
        final var updateData = Map.of(
                "id", id,
                "title", "A very good post",
                "content", "HCMS enables to really ease that work.");
        final var updated = client.post(null, "posts.update", updateData);
        assertTrue(updated.isOk(), updated::debug);
        assertEquals(updateData, updated.as(Map.class));

        // check a findById
        assertEquals(
                updateData,
                client.post(null, "posts.findById", Map.of("id", id)).as(Map.class));

        // just cleanup
        assertJsonRpcResultOk(client.post(null, "posts.deleteById", Map.of("id", id)));
    }

    @Test
    void revision(@Fusion final SimpleJsonRpcClient client) {
        assertJsonRpcResultOk(client.post(null, "entity-with-revision.create", Map.of("id", "1", "name", "first")));
        assertJsonRpcResultOk(client.post(null, "entity-with-revision.create", Map.of("id", "2", "name", "second")));
        assertJsonRpcResult(
                Map.of(
                        "total",
                        BigDecimal.valueOf(2),
                        "items",
                        List.of(Map.of("id", "1", "name", "first"), Map.of("id", "2", "name", "second"))),
                client.post(null, "entity-with-revision.findAll", Map.of()));

        for (int i = 0; i < 5; i++) {
            assertJsonRpcResultOk(
                    client.post(null, "entity-with-revision.update", Map.of("id", "2", "name", "second #" + i)));
            assertJsonRpcResult(
                    Map.of(
                            "total",
                            BigDecimal.valueOf(2),
                            "items",
                            List.of(Map.of("id", "1", "name", "first"), Map.of("id", "2", "name", "second #" + i))),
                    client.post(null, "entity-with-revision.findAll", Map.of()));
        }

        assertJsonRpcResult(
                Map.of("id", "2", "name", "second #4"),
                client.post(null, "entity-with-revision.findById", Map.of("id", "2")));

        assertJsonRpcResult(
                Map.of("success", true), client.post(null, "entity-with-revision.deleteById", Map.of("id", "2")));
        assertEquals(
                BigDecimal.valueOf(404),
                client.post(null, "entity-with-revision.findById", Map.of("id", "2"))
                        .as(Map.class)
                        .get("code"));

        // for cleanup only
        assertJsonRpcResult(
                Map.of("success", true), client.post(null, "entity-with-revision.deleteById", Map.of("id", "1")));
    }

    @Test
    void bulkFindByIdOk(@Fusion final SimpleJsonRpcClient client, @Fusion final JsonMapper jsonMapper)
            throws IOException, InterruptedException {
        final var baseRequest = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.toString(List.of(
                        Map.of(
                                "jsonrpc", "2.0",
                                "method", "posts.findById",
                                "params", Map.of("id", "00001")),
                        Map.of(
                                "jsonrpc", "2.0",
                                "method", "posts.findById",
                                "params", Map.of("id", "00001"))))))
                .uri(client.endpoint())
                .header("content-type", "application/json");
        final var response = client.client().send(baseRequest.build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        final var result = Map.of(
                "jsonrpc", "2.0", "result", Map.of("id", "00001", "title", "First post", "content", "HCMS rocks."));
        assertEquals(
                List.of(result, result),
                jsonMapper.fromString(new Types.ParameterizedTypeImpl(List.class, Object.class), response.body()));
    }

    @Test
    void bulkFindByIdFieldsFiltering(@Fusion final SimpleJsonRpcClient client, @Fusion final JsonMapper jsonMapper)
            throws IOException, InterruptedException {
        final var baseRequest = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.toString(List.of(
                        Map.of(
                                "jsonrpc", "2.0",
                                "method", "posts.findById",
                                "params", Map.of("id", "00001", "fields", List.of("title"))),
                        Map.of(
                                "jsonrpc", "2.0",
                                "method", "posts.findById",
                                "params", Map.of("id", "00001", "fields", List.of("title")))))))
                .uri(client.endpoint())
                .header("content-type", "application/json");
        final var response = client.client().send(baseRequest.build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        final var result = Map.of("jsonrpc", "2.0", "result", Map.of("title", "First post"));
        assertEquals(
                List.of(result, result),
                jsonMapper.fromString(new Types.ParameterizedTypeImpl(List.class, Object.class), response.body()));
    }

    @Test
    void bulkFindByIdFieldsFilteringWithId(
            @Fusion final SimpleJsonRpcClient client, @Fusion final JsonMapper jsonMapper)
            throws IOException, InterruptedException {
        final var baseRequest = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.toString(List.of(
                        Map.of(
                                "jsonrpc", "2.0",
                                "method", "posts.findById",
                                "params", Map.of("id", "00001", "fields", List.of("title", "id"))),
                        Map.of(
                                "jsonrpc", "2.0",
                                "method", "posts.findById",
                                "params", Map.of("id", "00001", "fields", List.of("title", "id")))))))
                .uri(client.endpoint())
                .header("content-type", "application/json");
        final var response = client.client().send(baseRequest.build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        final var result = Map.of("jsonrpc", "2.0", "result", Map.of("id", "00001", "title", "First post"));
        assertEquals(
                List.of(result, result),
                jsonMapper.fromString(new Types.ParameterizedTypeImpl(List.class, Object.class), response.body()));
    }

    private void assertJsonRpcResult(final Object expected, final SimpleJsonRpcClient.JsonRpcResponse response) {
        assertJsonRpcResultOk(response);
        assertEquals(expected, response.as(Map.class));
    }

    private void assertJsonRpcResultOk(final SimpleJsonRpcClient.JsonRpcResponse response) {
        assertTrue(response.isOk(), response::debug);
    }

    private boolean exists(final TransactionManager tx, final String id) {
        return tx.readSQL(c -> {
            try (final var s = c.createStatement();
                    final var r = s.executeQuery("select count(*) from post where id = '" + id + "'")) {
                return r.next() && r.getLong(1) > 0;
            }
        });
    }

    private String insertFakeData(final TransactionManager tx, final String id) {
        return tx.writeSQL(c -> {
            try (final var s = c.createStatement()) {
                s.execute("INSERT INTO POST(ID, TITLE, CONTENT) VALUES ('" + id + "', 'First post', 'HCMS rocks.')");
            }
            return id;
        });
    }
}
