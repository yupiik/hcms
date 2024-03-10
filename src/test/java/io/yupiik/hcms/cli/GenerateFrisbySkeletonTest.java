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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.main.Launcher;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerateFrisbySkeletonTest {
    @Test
    void generate(@TempDir final Path path) throws IOException {
        final var out = path.resolve("generated");
        Launcher.main(
                "generate-frisby-skeleton",
                "--hcms-modelLocation",
                "conf/model.json",
                "--hcms-database-url",
                "-",
                "--output",
                out.toString());
        assertTrue(Files.exists(out));

        final var files = new HashMap<String, String>();
        Files.walkFileTree(out, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                files.put(out.relativize(file).toString().replace(File.separatorChar, '/'), Files.readString(file));
                return super.visitFile(file, attrs);
            }
        });
        assertEquals(10, files.size());
        Stream.of(
                        "README.adoc",
                        "jest.config.js",
                        "package.json",
                        "__tests__/env.js",
                        "__tests__/setup.js",
                        "__tests__/teardown.js",
                        "__tests__/hcms/entities/posts.spec.js")
                .forEach(f -> assertTrue(files.containsKey(f), f));
    }
}
