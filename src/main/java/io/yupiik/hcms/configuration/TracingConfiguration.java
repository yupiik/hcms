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
package io.yupiik.hcms.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.util.List;

@RootConfiguration("tracing")
public record TracingConfiguration(
        @Property(documentation = "Is the tracing enabled.", defaultValue = "false") boolean enabled,
        @Property(documentation = "Is the tracing enabled.", defaultValue = "\"zipkin-client\"") String operation,
        @Property(value = "service", documentation = "Zipkin service name.", defaultValue = "\"zipkin-client\"")
                String service,
        @Property(value = "headers-span", documentation = "Zipkin span header name.", defaultValue = "\"X-B3-SpanId\"")
                String spanHeader,
        @Property(
                        value = "headers-trace",
                        documentation = "Zipkin trace header name.",
                        defaultValue = "\"X-B3-TraceId\"")
                String traceHeader,
        @Property(
                        value = "flush-timeout",
                        documentation = "How often to force a zipkin flush. Ignored if negative.",
                        defaultValue = "20_000L")
                long forceFlushTimeout,
        @Property(
                        value = "accumulator-buffer-size",
                        documentation = "How many spans can stay in memory before a forced flush.",
                        defaultValue = "2_048")
                int bufferSize,
        @Property(
                        documentation = "Zipkin URL(s) - a single successful call is kept but it enables failover.",
                        defaultValue = "java.util.List.of()")
                List<String> urls,
        @Property(
                        value = "skip-tls-checks",
                        documentation = "Should all SSL connections be supported over HTTPS (testing only).",
                        defaultValue = "false")
                boolean skipTLSChecks,
        @Property(
                        value = "timeout-connect",
                        documentation = "Connect timeout to Zipkin server.",
                        defaultValue = "20_000L")
                long connectTimeout,
        @Property(
                        value = "timeout-request",
                        documentation = "Request timeout to Zipkin server.",
                        defaultValue = "20_000L")
                long requestTimeout) {}
