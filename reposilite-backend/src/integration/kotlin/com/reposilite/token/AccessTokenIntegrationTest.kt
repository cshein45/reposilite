/*
 * Copyright (c) 2023 dzikoysk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("FunctionName")

package com.reposilite.token

import com.reposilite.RecommendedLocalSpecificationJunitExtension
import com.reposilite.RecommendedRemoteSpecificationJunitExtension
import com.reposilite.shared.ErrorResponse
import com.reposilite.token.AccessTokenPermission.MANAGER
import com.reposilite.token.AccessTokenType.PERSISTENT
import com.reposilite.token.api.AccessTokenDto
import com.reposilite.token.api.CreateAccessTokenResponse
import com.reposilite.token.api.CreateAccessTokenWithNoNameRequest
import com.reposilite.token.api.SecretType
import com.reposilite.token.specification.AccessTokenIntegrationSpecification
import io.javalin.http.HttpStatus.FORBIDDEN
import io.javalin.http.HttpStatus.OK
import kong.unirest.core.Unirest.delete
import kong.unirest.core.Unirest.get
import kong.unirest.core.Unirest.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(RecommendedLocalSpecificationJunitExtension::class)
internal class LocalAccessTokenIntegrationTest : AccessTokenIntegrationTest()

@ExtendWith(RecommendedRemoteSpecificationJunitExtension::class)
internal class RemoteAccessTokenIntegrationTest : AccessTokenIntegrationTest()

internal abstract class AccessTokenIntegrationTest : AccessTokenIntegrationSpecification() {

    @Test
    fun `should list tokens with entitled token`() {
        // given: existing tokens
        val (temporaryName, temporarySecret) = useDefaultManagementToken()
        val (persistentToken, persistentSecret) = useToken("persistent", "persistent-secret")

        // when: list of tokens is requested without valid access token
        val unauthorizedResponse = get("$base/api/tokens")
            .basicAuth(persistentToken.name, persistentSecret)
            .asJacksonObject(ErrorResponse::class)

        // then: request is rejected
        assertErrorResponse(FORBIDDEN, unauthorizedResponse)

        // when: list of tokens is requested with valid token
        val response = get("$base/api/tokens")
            .basicAuth(temporaryName, temporarySecret)
            .asJacksonObject(Array<AccessTokenDto>::class)

        // then: response contains list of all
        assertSuccessResponse(OK, response) { tokens ->
            assertThat(tokens.size).isEqualTo(2)
        }
    }

    @Test
    fun `should allow to check token details to entitled token`() {
        // given: existing tokens
        val (notAllowedToken, notAllowedSecret) = useToken("not-allowed", "secret")
        val (allowedToken, allowedSecret) = useToken("allowed", "secret")

        // when: tokens is requested without valid credentials
        val forbiddenResponse = get("$base/api/tokens/${allowedToken.name}")
            .basicAuth(notAllowedToken.name, notAllowedSecret)
            .asJacksonObject(ErrorResponse::class)

        // then: request is rejected by server
        assertErrorResponse(FORBIDDEN, forbiddenResponse)

        // when: token is requested with valid access token
        val response = get("$base/api/tokens/${allowedToken.name}")
            .basicAuth(allowedToken.name, allowedSecret)
            .asJacksonObject(AccessTokenDto::class)

        // then: request is rejected by server
        assertSuccessResponse(OK, response) {
            assertThat(it.name).isEqualTo(allowedToken.name)
        }
    }

    @Test
    fun `should generate a new token with entitled token`() {
        // given: existing tokens to use and details about token to create
        val (managerName, managerSecret) = useDefaultManagementToken()
        val (notAllowedToken, notAllowedSecret) = useToken("not-allowed", "secret")
        val (name, secret, permissions) = useTokenDescription("name", "secret", setOf(MANAGER))

        // when: not entitled token attempts to generate a new token
        val unauthorized = put("$base/api/tokens/$name")
            .basicAuth(notAllowedToken.name, notAllowedSecret)
            .body(CreateAccessTokenWithNoNameRequest(PERSISTENT, secret = secret, permissions = permissions.map { it.shortcut }.toSet()))
            .asJacksonObject(ErrorResponse::class)

        // then: the unauthorized request is rejected
        assertErrorResponse(FORBIDDEN, unauthorized)

        // when: valid token with management permission creates a new token
        val response = put("$base/api/tokens/$name")
            .basicAuth(managerName, managerSecret)
            .body(
                CreateAccessTokenWithNoNameRequest(
                    type = PERSISTENT,
                    secretType = SecretType.RAW,
                    secret = secret,
                    permissions = permissions.map { it.shortcut }.toSet(),
                    routes = setOf(
                        CreateAccessTokenWithNoNameRequest.Route("/private", setOf("r", "w")),
                        CreateAccessTokenWithNoNameRequest.Route("/public", setOf("r"))
                    )
                ),
            )
            .asJacksonObject(CreateAccessTokenResponse::class)

        // then: response contains valid token with generated secret
        assertSuccessResponse(OK, response) {
            assertThat(it.accessToken.name).isEqualTo(name)
            assertThat(it.secret).isEqualTo(secret)
            assertThat(getPermissions(it.accessToken.identifier)).isEqualTo(permissions)
            assertThat(getRoutes(it.accessToken.identifier)).isEqualTo(
                setOf(
                    Route("/private", RoutePermission.READ),
                    Route("/private", RoutePermission.WRITE),
                    Route("/public", RoutePermission.READ)
                )
            )
        }
    }

    @Test
    fun `should delete given token with entitled token`() {
        val (managerName, managerSecret) = useDefaultManagementToken()
        val (notAllowedToken, notAllowedSecret) = useToken("not-allowed", "secret")
        val (tokenToDelete) = useToken("token-to-delete", "secret")

        // when: not entitled token attempts to generate a new token
        val unauthorized = delete("$base/api/tokens/${tokenToDelete.name}")
            .basicAuth(notAllowedToken.name, notAllowedSecret)
            .asJacksonObject(ErrorResponse::class)

        // then: the unauthorized request is rejected
        assertErrorResponse(FORBIDDEN, unauthorized)

        // when: valid manager token creates a new token
        val response = delete("$base/api/tokens/${tokenToDelete.name}")
            .basicAuth(managerName, managerSecret)
            .asEmpty()

        // then: the given token should be deleted
        assertSuccessResponse(OK, response)
    }

}