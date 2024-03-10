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
package io.yupiik.hcms.jsonrpc.model;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

@JsonModel
public record Token(
        @Property(documentation = "Type of token should always be `Bearer`.") @JsonProperty("token_type")
                String tokenType,
        @Property(documentation = "Actual token to call resources.") @JsonProperty("access_token") String accessToken,
        @Property(documentation = "Refresh token, only usable to get a new token.") @JsonProperty("refresh_token")
                String refreshToken,
        @Property(
                        documentation =
                                "Expiration duration - advice: never use it and prefer the expiry date in the payload of the JWT `access_token`/`refresh_token`.")
                long expires_in) {}
