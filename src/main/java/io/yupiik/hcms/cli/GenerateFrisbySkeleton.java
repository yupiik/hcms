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
package io.yupiik.hcms.cli;

import static io.yupiik.hcms.service.model.json.Model.JsonRpcMethodType.CRUD;
import static io.yupiik.hcms.service.model.json.Model.JsonSchemaType.nullValue;
import static io.yupiik.hcms.service.model.json.Model.JsonSchemaType.string;
import static java.util.Locale.ROOT;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.hcms.service.model.Entity;
import io.yupiik.hcms.service.model.ModelHandler;
import io.yupiik.hcms.service.model.ModelLoader;
import io.yupiik.hcms.service.model.json.Model;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

@Command(
        name = "generate-frisby-skeleton",
        description =
                "Create a NPM project in a configured folder where skeleton of FrisbyJS tests are configured for the HCMS provided configuration.")
public class GenerateFrisbySkeleton implements Runnable {
    private final Logger logger = Logger.getLogger(getClass().getName());

    private final FrisbyConfiguration configuration;
    private final ModelHandler modelHandler;
    private final ModelLoader.DynamicModel model;

    public GenerateFrisbySkeleton(
            final FrisbyConfiguration configuration,
            final ModelHandler modelHandler,
            final ModelLoader.DynamicModel model) {
        this.configuration = configuration;
        this.modelHandler = modelHandler;
        this.model = model;
    }

    @Override
    public void run() {
        final var currentModel = model.get();
        final var jsonRpcMethods = currentModel.jsonRpcMethods();
        if (jsonRpcMethods == null || jsonRpcMethods.isEmpty()) {
            throw new IllegalArgumentException("No jsonRpcMethods");
        }

        final var base = Path.of(configuration.output());
        final var output = base.resolve("__tests__/hcms/entities");
        try {
            if (!Files.exists(output)) {
                Files.createDirectories(output);
                logger.info(() -> "Created '" + output + "'");
            }

            setupProject(base);
            generateHCMSServerLifecycle(base);

            jsonRpcMethods.stream()
                    .collect(groupingBy(
                            m -> modelHandler.entities().get(m.entityName()),
                            collectingAndThen(toList(), l -> l.stream()
                                    .map(m -> m.type() == null ? CRUD : m.type())
                                    .collect(toSet()))))
                    .forEach((entity, methods) -> {
                        try {
                            final var target = output.resolve(entity.name() + ".spec.js");
                            Files.createDirectories(target.getParent());
                            Files.writeString(
                                    target,
                                    generate(
                                            // todo: refine security need, will mainly work for CRUD for now
                                            jsonRpcMethods.stream()
                                                    .filter(it -> Objects.equals(it.entityName(), entity.name()))
                                                    .findFirst()
                                                    .map(Model.JsonRpcMethod::security)
                                                    .orElse(null),
                                            entity,
                                            methods));
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
        } catch (final IOException ioe) {
            throw new IllegalStateException(ioe);
        }
    }

    private String generate(
            final Model.JsonRpcMethodSecurity spec,
            final Entity entity,
            final Collection<Model.JsonRpcMethodType> methods) {
        final var ids = entity.identifiers();
        final var properties = entity.schema().properties();
        final var idValues = ids.stream()
                .map(id -> "                '" + id + "': created['" + id + "'],\n")
                .collect(joining());

        final boolean crud = methods.contains(CRUD);
        final var create = crud || methods.contains(Model.JsonRpcMethodType.CREATE);

        final var createData = !create
                ? ""
                : properties.entrySet().stream()
                        .filter(Predicate.not(id -> ids.contains(id.getKey())))
                        .filter(it -> entity.createVirtualFields() == null
                                || !entity.createVirtualFields().containsKey(it.getKey()))
                        .map(it -> generateDataFor(it, true))
                        .sorted()
                        .collect(joining());

        return "const { jsonRpc, Joi /* , frisby, login, jsonrpcUrl */  } = require('../../env');\n"
                + "const expects = require('frisby/src/frisby/expects');\n\n"
                + "describe('"
                + entity.name() + " entity', () => {\n"
                + "    // IMPORTANT: assumes the entity has no initial provisioning,\n"
                + "    //            the describe block enables us to run these tests sequentially\n"
                + "    //            since they are actually chained in the skaffolding\n"
                + "\n"
                + "    const schema = {\n"
                + properties.entrySet().stream()
                        .map(e -> "        '" + e.getKey() + "': " + joiConstraints(e.getValue()) + ",\n")
                        .collect(joining())
                + "    };\n"
                + (crud || methods.contains(Model.JsonRpcMethodType.FIND_ALL) ? findAllEmpty(spec, entity) : "")
                + (crud || methods.contains(Model.JsonRpcMethodType.FIND_BY_ID)
                        ? findByIdMissing(spec, entity, ids, properties)
                        : "")
                + (create ? create(spec, entity, createData) : "")
                + (create && (crud || methods.contains(Model.JsonRpcMethodType.FIND_BY_ID))
                        ? findById(spec, entity, idValues, createData)
                        : "")
                + (create && (crud || methods.contains(Model.JsonRpcMethodType.UPDATE))
                        ? update(spec, entity, ids, idValues, properties)
                        : "")
                + (create && (crud || methods.contains(Model.JsonRpcMethodType.FIND_ALL))
                        ? findAllPage1(spec, entity)
                        : "")
                + (create && (crud || methods.contains(Model.JsonRpcMethodType.FIND_ALL))
                        ? deleteById(spec, entity, idValues)
                        : "")
                + "});\n\n";
    }

    private String update(
            final Model.JsonRpcMethodSecurity spec,
            final Entity entity,
            final Collection<String> ids,
            final String idValues,
            final Map<String, Model.JsonSchema> properties) {
        final var updateData = idValues
                + properties.entrySet().stream()
                        .filter(Predicate.not(id -> ids.contains(id.getKey())))
                        .filter(it -> entity.updateVirtualFields() == null
                                || !entity.updateVirtualFields().containsKey(it.getKey()))
                        .map(it -> generateDataFor(it, false))
                        .sorted()
                        .collect(joining());
        return "\n    it('"
                + entity.name() + ".update', async () => {\n"
                + "        const res = await jsonRpc({\n"
                + "            jsonrpc: '2.0',\n"
                + "            method: '"
                + entity.name() + ".update',\n            params: {\n"
                + updateData
                + "            },\n"
                + "        }" + authParam(spec, Model.JsonRpcMethodSecurity::update) + ");\n"
                + "        expect(res.status).toBe(200)\n        expect(res.json.error).toBeUndefined();\n"
                + "        expect(res.json.result)\n"
                + "            .toEqual(expect.objectContaining({\n"
                + updateData
                + "        }));\n"
                + "        expects.jsonTypes(res, 'result', schema);\n    });\n";
    }

    private String create(final Model.JsonRpcMethodSecurity spec, final Entity entity, final String createData) {
        return "\n    let created;\n"
                + "    it('"
                + entity.name() + ".create', async () => {\n"
                + "        const res = await jsonRpc({\n"
                + "            jsonrpc: '2.0',\n"
                + "            method: '"
                + entity.name() + ".create',\n" + "            params: {\n"
                + createData
                + "            },\n"
                + "        }" + authParam(spec, Model.JsonRpcMethodSecurity::create) + ");\n"
                + "        expect(res.status).toBe(200)\n" + "        expect(res.json.error).toBeUndefined();\n"
                + "        expect(res.json.result)\n"
                + "            .toEqual(expect.objectContaining({\n"
                + createData
                + "            }));\n"
                + "        expects.jsonTypes(res, 'result', schema);\n"
                + "        created = res.json.result;\n"
                + "    });\n";
    }

    private String deleteById(final Model.JsonRpcMethodSecurity spec, final Entity entity, final String idValues) {
        return "\n    it('"
                + entity.name() + ".deleteById', async () => {\n"
                + "        const res = await jsonRpc({\n"
                + "            jsonrpc: '2.0',\n"
                + "            method: '"
                + entity.name() + ".deleteById',\n"
                + "            params: {\n"
                + idValues
                + "            },\n"
                + "        }" + authParam(spec, Model.JsonRpcMethodSecurity::delete) + ");\n"
                + "        expect(res.status).toBe(200)\n" + "        expect(res.json.error).toBeUndefined();\n"
                + "        expect(res.json.result).toStrictEqual({ success: true });\n"
                + "    });\n";
    }

    private String findByIdMissing(
            final Model.JsonRpcMethodSecurity spec,
            final Entity entity,
            final Collection<String> ids,
            final Map<String, Model.JsonSchema> properties) {
        return "\n    it('"
                + entity.name() + ".findById not found', async () => {\n"
                + "        const res = await jsonRpc({\n"
                + "            jsonrpc: '2.0',\n"
                + "            method: '"
                + entity.name() + ".findById',\n" + "            params: {\n"
                + ids.stream()
                        .map(id -> "                " + id + ": "
                                + (switch (findType(properties.get(id))) {
                                    case string -> "'xxx_id_missing'";
                                    case integer, number -> "-1";
                                    default -> throw new IllegalStateException(
                                            "Unsupported identifier type for '" + entity.name() + "#" + id + "'");
                                })
                                + ",\n")
                        .sorted()
                        .collect(joining())
                + "            },\n"
                + "        }" + authParam(spec, Model.JsonRpcMethodSecurity::view) + ");\n"
                + "        expect(res.status).toBe(200);\n"
                + "        expect(res.json.result).toBeUndefined();\n"
                + "        expect(res.json.error)\n" + "            .toEqual(expect.objectContaining({\n"
                + "                code: 404,\n"
                + "                message: 'Entity not found',\n"
                + "            }));\n"
                + "    });\n";
    }

    private String findById(
            final Model.JsonRpcMethodSecurity spec,
            final Entity entity,
            final String idValues,
            final String createData) {
        return "\n    it('"
                + entity.name() + ".findById found', async () => {\n" + "        const res = await jsonRpc({\n"
                + "            jsonrpc: '2.0',\n"
                + "            method: '"
                + entity.name() + ".findById',\n" + "            params: {\n"
                + idValues
                + "            },\n"
                + "        }" + authParam(spec, Model.JsonRpcMethodSecurity::view) + ");\n"
                + "        expect(res.status).toBe(200)\n" + "        expect(res.json.error).toBeUndefined();\n"
                + "        expect(res.json.result)\n"
                + "            .toEqual(expect.objectContaining({\n"
                + createData
                + "            }));\n"
                + "        expects.jsonTypes(res, 'result', schema);\n"
                + "    });\n";
    }

    private String findAllPage1(final Model.JsonRpcMethodSecurity spec, final Entity entity) {
        return "\n    it('"
                + entity.name() + ".findAll empty', async () => {\n"
                + "        const res = await jsonRpc({\n"
                + "            jsonrpc: '2.0',\n"
                + "            method: '"
                + entity.name() + ".findAll',\n"
                + "            params: {\n"
                + "                page: 1,\n"
                + "                pageSize: 3,\n"
                + "            },\n"
                + "        }" + authParam(spec, Model.JsonRpcMethodSecurity::view) + ");\n"
                + "        expect(res.status).toBe(200)\n"
                + "        expect(res.json.error).toBeUndefined();\n"
                + "        expect(res.json.result).toEqual(expect.objectContaining({ total: 1 }));\n"
                + "        expects.jsonTypes(res, 'result.items.*', schema);\n"
                + "    });\n";
    }

    private String findAllEmpty(final Model.JsonRpcMethodSecurity spec, final Entity entity) {
        return "\n    it('"
                + entity.name() + ".findAll empty', async () => {\n"
                + "        const res = await jsonRpc({\n"
                + "            jsonrpc: '2.0',\n"
                + "            method: '"
                + entity.name() + ".findAll',\n"
                + "            params: {\n"
                + "                page: 1,\n"
                + "                pageSize: 3,\n"
                + "            },\n"
                + "        }" + authParam(spec, Model.JsonRpcMethodSecurity::view) + ");\n"
                + "        expect(res.status).toBe(200)\n"
                + "        expect(res.json.error).toBeUndefined();\n"
                + "        expect(res.json).toStrictEqual({ jsonrpc: '2.0', result: { total: 0, items: [] } });\n"
                + "    });\n";
    }

    private String authParam(
            final Model.JsonRpcMethodSecurity spec,
            final Function<Model.JsonRpcMethodSecurity, Model.SecurityValidation> validation) {
        if (spec == null) {
            return "";
        }
        final var security = validation.apply(spec);
        if (security == null || security.anonymous()) {
            return "";
        }
        return ", true"; // for now use admin
    }

    private String joiConstraints(final Model.JsonSchema schema) {
        var result =
                switch (findType(schema)) {
                    case string -> "Joi.string()";
                    case number -> "Joi.number()";
                    case integer -> "Joi.integer()";
                    case bool -> "Joi.boolean()";
                    case array -> "Joi.array()";
                    case object -> "Joi.object()";
                    case nullValue -> "Joi.any().optional()";
                };
        if (schema.type() != null && !schema.type().contains(nullValue)) {
            result = result + ".required()";
        }
        return result;
    }

    private void setupProject(final Path base) throws IOException {
        Files.writeString(
                base.resolve("package.json"),
                """
                        {
                          "private": true,
                          "description": "HCMS API tests",
                          "devDependencies": {
                            "frisby": "^2.1.3",
                            "jest": "^29.7.0",
                            "jest-html-reporters": "^3.1.7"
                          },
                          "scripts": {
                            "test": "jest --verbose=false"
                          }
                        }""");
        Files.writeString(
                base.resolve("README.adoc"),
                """
                        = HCMS API Tests

                        To initialize the repository run `npm install` then to run tests run `npm test`.

                        The default enables to use HCMS binary (native for linux) but you can switch to java mode with the following steps:

                        . Install Java 21 and ensure it is setup in your `PATH`,
                        . Update the test command in `package.json` to setup java as command and the HCMS classpath: `HCMS_BINARY=$JAVA_HOME/bin/java HCMS_CLASSPATH=.... npm test`

                        By default, once tests are executed you get a report in `dist/`.
                        """);
        Files.writeString(
                base.resolve("jest.config.js"),
                """
                        module.exports = {
                            globalSetup: './__tests__/setup.js',
                            globalTeardown: './__tests__/teardown.js',
                            testEnvironment: 'node',
                            testPathIgnorePatterns: ['/node_modules/'],
                            testMatch: ['**/__tests__/**/*.spec.js'],
                            testTimeout: 10000,
                            reporters: [
                                'default',
                                [
                                    './node_modules/jest-html-reporters',
                                    {
                                        pageTitle: 'HCMS API Test report',
                                        publicPath: './dist',
                                        filename: 'report.html',
                                    },
                                ],
                            ],
                        };
                        """);
        Files.writeString(
                base.resolve("__tests__/env.js"),
                """
                        const frisby = require('frisby');
                        const { addMsg } = require("jest-html-reporters/helper");

                        // IMPORTANT: ensure to add "ddl/01-create-database.h2.sql" to model.sql#sql array
                        //            or adjust this login default phase to your actual user dataset
                        const admin = {
                            username: 'admin@app.com',
                            password: '@dm1n!',
                        };

                        frisby.globalSetup({
                            request: {
                                headers: {
                                    'accept': 'application/json',
                                }
                            }
                        });

                        const jsonrpcUrl = globalThis.HCMS_URL;

                        const plainJsonRpc = async (body, opts = {}) => {
                            const res = await frisby.post(jsonrpcUrl, { body: JSON.stringify(body), ...opts });
                            await addMsg({
                                message: JSON.stringify({
                                    request: body,
                                    response: {
                                        status: res.status,
                                        json: res.json,
                                    },
                                }, null, 2),
                            });
                            return res;
                        };

                        const cachedTokens = {};
                        async function login(params = admin) {
                            const key = `${params.username}:${params.password}`;
                            const existing = cachedTokens[key];
                            if (existing) { // assume the suite is executed within the token validation - should be ok
                                return Promise.resolve(existing);
                            }

                            const spec = await plainJsonRpc(
                                {
                                    jsonrpc: '2.0',
                                    method: 'hcms.security.login',
                                    params,
                                });
                            expect(spec.status).toBe(200);

                            if (!spec.json || spec.json.error) {
                                throw new Error(`Invalid login with user=${params.username}: ${JSON.stringify(spec.json, null, 2)}`);
                            }

                            cachedTokens[key] = spec.json;
                            return cachedTokens[key];
                        }

                        const jsonRpc = async (body, user) => {
                            if (user) {
                                const auth = await login(user === true ? admin : user);
                                return plainJsonRpc(body, { headers: { authorization: `Bearer ${auth.result.access_token}` } });
                            }
                            return plainJsonRpc(body);
                        };

                        module.exports = {
                            jsonrpcUrl,
                            login,
                            frisby,
                            jsonRpc,
                            admin,
                            Joi: frisby.Joi,
                        };
                        """);
    }

    private void generateHCMSServerLifecycle(final Path base) throws IOException {
        Files.writeString(
                base.resolve("__tests__/setup.js"),
                """
                        // for managed case, how to run HCMS server
                        const defaultCommand = process.env.HCMS_BINARY || 'hcms'; // can be "java" if you set $HCMS_CLASSPATH and have a contextual java 21 JRE
                        const isJava = defaultCommand.substring(defaultCommand.lastIndexOf('/') + 1).indexOf('java') >= 0;
                        const defaultOptions = [ // here you can add any configuration to launch the server
                            ...(isJava ? ['-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:8000', '-cp', process.env.HCMS_CLASSPATH, '-Djava.util.logging.manager=io.yupiik.logging.jul.YupiikLogManager'] : []),
                            '-Djava.net.preferIPv4Stack=true',
                            // random ports
                            `-Dfusion.http-server.port=${process.env.HCMS_PORT || '0'}`, // 0 means random port
                            `-Dfusion.observability.server.port=${process.env.HCMS_OBSERVABILITY_PORT || '0'}`,
                            // model location - by default relative to pwd
                            `-Dhcms.modelLocation=${process.env.HCMS_MODEL || 'conf/model.json'}`,
                            // default database
                            `-Dhcms.database.driver=${process.env.HCMS_DATABASE_DRIVER || 'org.h2.Driver'}`,
                            `-Dhcms.database.url=${process.env.HCMS_DATABASE_URL || 'jdbc:h2:mem:hcms_test'}`,
                            `-Dhcms.database.username=${process.env.HCMS_DATABASE_USERNAME || 'sa'}`,
                            `-Dhcms.database.password=${process.env.HCMS_DATABASE_PASSWORD || ''}`,
                            // default security if you need to login during tests
                            '-Dhcms.security.privateKey=MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAJVadEdJh+Gds6RtZZv937FJPS4XdYm3BMSSIiFFZPqeYwQeKiqGkEo65PFdeD7mmmPZo8tiZX43lN9cZiJgygLAGCknPuocSaf0/rpLdi78L+0XRTWIrY0y5tWMnNcD1bmEpWyl5x50FT6JW3etGfFfpQrAHSOkgd2R+V19FwjzAgMBAAECgYAR3hITxoUzWurMh1Xk6o33UfwNZpBmMEypY5N3stXuHEKw5xbuTXjiQyzJKgB3rfOBxzNkN9pNK5hrfEyvsi/tzgwjp9V8ApbmotiYViPLtiST3WILpApbNI6/dP0iM98t29RfXBrRaEWD709CreO5S11FWBkU+2a8+hyYz7GE2QJBALUQulTj5p2QeUDEuqBI+vOwvIOfngHExkt9n8UnHlbdWHCJib2QxHjiAVDb4DHYog5KT28eMT2acFItom9NX88CQQDTKfHMoEMWUS3zTVKRq9pidCGn/eRi33EC1wRlijs0u/t/uKbYdnmTAt1I8AXOe2FZeiQo5YfHSj15TGcNqwmdAkEAlx0m5cJurgHtsIh/2VYPW2Kdcpy8mm1HsaletoQ3ZffF3+Zp9rPjxZ+ZyYo4SmGqnpKWSP7BydAi/fLoJkxFMQJAaDKzaWjPkeyfAwbtroohqiFqFi5Xi158so0NU1mhm4UDNmQUmI3lseBg90PRabFCOVfnDfMtS+7bZMaJt5nllQJAaCcR5CoWgqEIHijv0PK0SjmlVRzU5lwRMMi636E6o/gNxnY9tav+GCK9phuTYyrW6BPtbDJvz2N4hVtyTWZW2Q==',
                            '-Dhcms.security.kid=frisby',
                            '-Dhcms.security.keys.length=1',
                            '-Dhcms.security.keys.0.kid=frisby',
                            '-Dhcms.security.keys.0.use=sig',
                            '-Dhcms.security.keys.0.kty=RSA',
                            '-Dhcms.security.keys.0.alg=RS256',
                            '-Dhcms.security.keys.0.n=MTA0ODc5NDc5NzU2ODk3NjYyOTI0ODM4MjY5Njg4MDM3NjE4NDYxNDI2NDE0Mzg3NDk1ODYyODcwNDYxMzk2ODg3ODM4NDc0OTczNDgxMjE5MDA0NzkxNTM3NTMzNTgxODg5NDQ2NTQwODA3NzQwMjcyNTY0MDMzNDU0OTEwNTM3OTE3MTYyOTA3NTA0MTc0ODQxMDg4ODQ2MDYwNTExNzYzODUzNjE2MTA1MjYyMDYxNDYyMDk1NzQxODA4MjI5MjczNjk0MTYyMzYyODc4MjAyNjAzOTczOTg5NzkxMDg0MTc3MjQ5MDkxMzE5NzE2ODczMDk5Njk3ODQ4NzczMTMwNTA1NzU1MDE1NDM5MzA4ODc0ODk3NDI0OTkxODMyNzYzNDEyMjQ4MDI2MzAxMjcwMjU5',
                            '-Dhcms.security.keys.0.e=NjU1Mzc=',
                            '-Dhcms.security.keys.0.x5c=-----BEGIN PUBLIC KEY-----\\nMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCVWnRHSYfhnbOkbWWb/d+\\nxST0uF3WJtwTEkiIhRWT6nmMEHioqhpBKOuTxXXg+5ppj2aPLYmV+N5TfXG\\nYiYMoCwBgpJz7qHEmn9P66S3Yu/C/tF0U1iK2NMubVjJzXA9W5hKVspeced\\nBU+iVt3rRnxX6UKwB0jpIHdkfldfRcI8wIDAQAB\\n-----END PUBLIC KEY-----',
                            ...(isJava ? ['io.yupiik.fusion.framework.api.main.Launcher'] : []),
                        ];

                        if (!process.env.HCMS_BINARY) {
                            console.warn('HCMS_BINARY environment variable is not set, falling back on plain `hcms` but it is recommended to ensure it is explicitly set');
                        }

                        module.exports = function setup() {
                            return new Promise((resolve, ko) => {
                                // enable to run tests on an already running instance
                                if (!!process.env.HCMS_MANAGED) {
                                    console.log(`Using assumed running HCMS on port ${port}`);
                                    resolve(true);
                                    return;
                                }

                                const ignoreLog = process.env.HCMS_IGNORE_LOGS === 'true';
                                let resolved = false;
                                let port = undefined;
                                const portLineMarker = 'Starting ProtocolHandler ["http-nio-auto-1-';
                                const startedLineMarker = 'Tracing is not enabled.';
                                function onData(logger, stream, data) {
                                    const line = data.toString();
                                    if (line.includes(portLineMarker)) {
                                        const from = line.indexOf(portLineMarker) + portLineMarker.length;
                                        port = +line.substring(from, line.indexOf('"]', from));
                                        globalThis.HCMS_URL = `http://localhost:${port}/jsonrpc`;
                                    } else if (line.includes(startedLineMarker)) {
                                        resolved = true;
                                        resolve(true);
                                    }

                                    if (!ignoreLog) {
                                        line.split('\\n').filter(it => it.length > 0).forEach(it => logger(`[hcms.${stream}]: ${it}`));
                                    }
                                }

                                const { spawn } = require('node:child_process');

                                console.log(); // flush jest output before logging to keep a clean output
                                const server = spawn(defaultCommand, defaultOptions, { cwd: __dirname + '/..' });
                                server.stdout.on('data', data => { onData(console.log, 'out', data); });
                                server.stderr.on('data', data => { onData(console.log, 'err', data); });
                                server.on('close', code => {
                                    if (code !== 0 && code !== 143 /* killed */) {
                                        if (!resolved) { ko(false); } console.log(`HCMS exited with status ${code}`);
                                    }
                                });

                                // for teardown
                                globalThis.HCMS_PID = server.pid;
                            });
                        };
                        """);
        Files.writeString(
                base.resolve("__tests__/teardown.js"),
                """
                        module.exports = function teardown() {
                            process.kill(globalThis.HCMS_PID);
                        };
                        """);
    }

    private Model.JsonSchemaType findType(final Model.JsonSchema schema) {
        if (schema == null || schema.type() == null || schema.type().isEmpty()) {
            return string;
        }
        return schema.type().stream().filter(it -> it != nullValue).findFirst().orElse(string);
    }

    private String findSampleValueFor(final String name, final boolean init, final Model.JsonSchema schema) {
        return (switch (findType(schema)) {
            case string -> switch (name.toLowerCase(ROOT)
                    .replace("_", "")
                    .replace("-", "")
                    .replace(" ", "")) {
                case "name" -> init ? "'Gabriel Jade'" : "'Robert Son'";
                case "firstname" -> init ? "'Gabriel'" : "'Robert'";
                case "lastname" -> init ? "'Jade'" : "'Son'";
                case "middlename" -> "'Junior'";
                case "phone", "phonenumber" -> init ? "'0123456789'" : "'0123456788'";
                case "birthdate" -> "'2000-01-01'";
                case "password" -> init ? "'Sup3rS3!Y'" : "'Sup3rS3!Y2'";
                default -> init ? "'some value'" : "'some other value'";
            };
            case integer, number -> "123";
            case bool -> "false";
            case nullValue -> "null";
            case object -> "{}";
            case array -> "[]";
        });
    }

    private String generateDataFor(final Map.Entry<String, Model.JsonSchema> it, final boolean init) {
        return "                '" + it.getKey() + "': " + findSampleValueFor(it.getKey(), init, it.getValue()) + ",\n";
    }

    @RootConfiguration("generate-frisby-skeleton")
    public record FrisbyConfiguration(
            @Property(
                            documentation = "Where to generate the skeleton (`generate-frisby-skeleton` command only).",
                            defaultValue = "\"hcms-frisby\"")
                    String output) {}
}
