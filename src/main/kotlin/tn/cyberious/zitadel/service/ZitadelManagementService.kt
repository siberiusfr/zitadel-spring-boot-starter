package tn.cyberious.zitadel.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import tn.cyberious.zitadel.config.ZitadelProperties
import tn.cyberious.zitadel.exception.ZitadelException
import tn.cyberious.zitadel.models.*
import java.io.StringReader
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.*

class ZitadelManagementService(
    private val properties: ZitadelProperties,
) {
    private val log = LoggerFactory.getLogger(ZitadelManagementService::class.java)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val restClient: RestClient = RestClient.builder()
        .baseUrl(properties.domain)
        .build()

    private var cachedToken: String? = null
    private var tokenExpiry: Instant = Instant.MIN

    private val serviceAccountKey: JsonNode by lazy {
        objectMapper.readTree(properties.serviceAccountKeyJson)
    }

    // ==================== Authentication ====================

    @Synchronized
    fun getAccessToken(): String {
        // Priority: PAT > JWT Profile > Client Credentials
        if (properties.personalAccessToken.isNotBlank()) {
            return properties.personalAccessToken
        }
        if (properties.serviceAccountKeyJson.isNotBlank()) {
            return getJwtProfileToken()
        }
        if (properties.clientId.isNotBlank() && properties.clientSecret.isNotBlank()) {
            return getClientCredentialsToken()
        }
        throw ZitadelException("No authentication method configured", 401)
    }

    private fun getJwtProfileToken(): String {
        val now = Instant.now()
        if (cachedToken != null && now.isBefore(tokenExpiry.minusSeconds(60))) {
            return cachedToken!!
        }

        val userId = serviceAccountKey["userId"].asText()
        val keyId = serviceAccountKey["keyId"].asText()
        val privateKeyPem = serviceAccountKey["key"].asText()

        val privateKey = parseRsaPrivateKey(privateKeyPem)

        val expiry = now.plusSeconds(3600)
        val claims = JWTClaimsSet.Builder()
            .issuer(userId)
            .subject(userId)
            .audience(properties.domain)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expiry))
            .build()

        val header = JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(keyId)
            .build()

        val signedJwt = SignedJWT(header, claims)
        signedJwt.sign(RSASSASigner(privateKey))
        val assertion = signedJwt.serialize()

        return exchangeToken(
            "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "scope" to "openid urn:zitadel:iam:org:project:id:zitadel:aud",
            "assertion" to assertion,
        )
    }

    private fun getClientCredentialsToken(): String {
        val now = Instant.now()
        if (cachedToken != null && now.isBefore(tokenExpiry.minusSeconds(60))) {
            return cachedToken!!
        }

        return exchangeToken(
            "grant_type" to "client_credentials",
            "client_id" to properties.clientId,
            "client_secret" to properties.clientSecret,
            "scope" to "openid urn:zitadel:iam:org:project:id:zitadel:aud",
        )
    }

    private fun exchangeToken(vararg params: Pair<String, String>): String {
        val formData = LinkedMultiValueMap<String, String>()
        params.forEach { (k, v) -> formData.add(k, v) }

        val tokenResponse = restClient.post()
            .uri("/oauth/v2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(formData)
            .retrieve()
            .onStatus({ it.isError }) { _, response ->
                val body = response.body.bufferedReader().readText()
                throw ZitadelException("Token exchange failed: $body", response.statusCode.value())
            }
            .body(JsonNode::class.java)!!

        cachedToken = tokenResponse["access_token"].asText()
        val expiresIn = tokenResponse["expires_in"]?.asLong() ?: 3600
        tokenExpiry = Instant.now().plusSeconds(expiresIn)

        log.debug("Zitadel token refreshed, expires in {}s", expiresIn)
        return cachedToken!!
    }

    private fun parseRsaPrivateKey(pem: String): RSAPrivateKey {
        val normalizedPem = pem.replace("\\n", "\n")
        PEMParser(StringReader(normalizedPem)).use { parser ->
            val pemObject = parser.readObject()
            val converter = JcaPEMKeyConverter()
            return when (pemObject) {
                is PrivateKeyInfo -> converter.getPrivateKey(pemObject) as RSAPrivateKey
                is PEMKeyPair -> converter.getKeyPair(pemObject).private as RSAPrivateKey
                else -> throw IllegalArgumentException(
                    "Unsupported PEM key format: ${pemObject?.javaClass?.name}"
                )
            }
        }
    }

    // ==================== HTTP Helpers ====================

    private fun <T> apiGet(uri: String, responseType: Class<T>, orgId: String? = null): T {
        val spec = restClient.get()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${getAccessToken()}")
        orgId?.let { spec.header("x-zitadel-orgid", it) }
        return spec.retrieve()
            .onStatus({ it.isError }) { _, response ->
                handleErrorResponse(response)
            }
            .body(responseType)!!
    }

    private fun <T> apiPost(uri: String, body: Any, responseType: Class<T>, orgId: String? = null): T {
        val spec = restClient.post()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${getAccessToken()}")
            .contentType(MediaType.APPLICATION_JSON)
        orgId?.let { spec.header("x-zitadel-orgid", it) }
        return spec.body(body)
            .retrieve()
            .onStatus({ it.isError }) { _, response ->
                handleErrorResponse(response)
            }
            .body(responseType)!!
    }

    private fun <T> apiPut(uri: String, body: Any, responseType: Class<T>, orgId: String? = null): T {
        val spec = restClient.put()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${getAccessToken()}")
            .contentType(MediaType.APPLICATION_JSON)
        orgId?.let { spec.header("x-zitadel-orgid", it) }
        return spec.body(body)
            .retrieve()
            .onStatus({ it.isError }) { _, response ->
                handleErrorResponse(response)
            }
            .body(responseType)!!
    }

    private fun apiPostNoContent(uri: String, body: Any, orgId: String? = null) {
        val spec = restClient.post()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${getAccessToken()}")
            .contentType(MediaType.APPLICATION_JSON)
        orgId?.let { spec.header("x-zitadel-orgid", it) }
        spec.body(body)
            .retrieve()
            .onStatus({ it.isError }) { _, response ->
                handleErrorResponse(response)
            }
            .body(JsonNode::class.java)
    }

    private fun handleErrorResponse(response: org.springframework.http.client.ClientHttpResponse) {
        val body = response.body.bufferedReader().readText()
        val errorNode = try {
            objectMapper.readTree(body)
        } catch (_: Exception) {
            null
        }
        val code = errorNode?.get("code")?.asText()
        val message = errorNode?.get("message")?.asText() ?: body
        throw ZitadelException(message, response.statusCode.value(), code)
    }

    // ==================== Organizations ====================

    fun createOrganization(name: String): ZitadelOrganization {
        val response = apiPost(
            "/v2/organizations",
            mapOf("name" to name),
            JsonNode::class.java
        )
        return ZitadelOrganization(
            id = response["organizationId"]?.asText() ?: "",
            name = name,
            state = response["details"]?.get("state")?.asText(),
            creationDate = response["details"]?.get("creationDate")?.asText(),
        )
    }

    fun getOrganization(orgId: String): ZitadelOrganization {
        val response = apiGet(
            "/management/v1/orgs/me",
            JsonNode::class.java,
            orgId
        )
        val org = response["org"] ?: response
        return ZitadelOrganization(
            id = org["id"]?.asText() ?: orgId,
            name = org["name"]?.asText() ?: "",
            state = org["state"]?.asText(),
            creationDate = org["details"]?.get("creationDate")?.asText(),
        )
    }

    fun deactivateOrganization(orgId: String) {
        apiPostNoContent("/admin/v1/orgs/$orgId/_deactivate", emptyMap<String, Any>())
    }

    fun reactivateOrganization(orgId: String) {
        apiPostNoContent("/admin/v1/orgs/$orgId/_reactivate", emptyMap<String, Any>())
    }

    // ==================== Users ====================

    fun createHumanUser(
        orgId: String,
        email: String,
        firstName: String,
        lastName: String,
        password: String? = null,
        isEmailVerified: Boolean = true,
    ): ZitadelUser {
        val body = mutableMapOf<String, Any>(
            "profile" to mapOf(
                "givenName" to firstName,
                "familyName" to lastName,
            ),
            "email" to mapOf(
                "email" to email,
                "isVerified" to isEmailVerified,
            ),
        )
        if (password != null) {
            body["password"] = mapOf(
                "password" to password,
                "changeRequired" to false,
            )
        }
        val response = apiPost("/v2/users/human", body, JsonNode::class.java, orgId)
        return ZitadelUser(
            id = response["userId"]?.asText() ?: "",
            username = email,
            email = email,
            firstName = firstName,
            lastName = lastName,
            state = response["details"]?.get("state")?.asText(),
            creationDate = response["details"]?.get("creationDate")?.asText(),
        )
    }

    fun createMachineUser(orgId: String, username: String, name: String): ZitadelUser {
        val body = mapOf(
            "userName" to username,
            "name" to name,
        )
        val response = apiPost("/management/v1/users/machine", body, JsonNode::class.java, orgId)
        return ZitadelUser(
            id = response["userId"]?.asText() ?: "",
            username = username,
            state = response["details"]?.get("state")?.asText(),
            creationDate = response["details"]?.get("creationDate")?.asText(),
        )
    }

    fun getUserById(userId: String, orgId: String? = null): ZitadelUser {
        val response = apiGet("/v2/users/$userId", JsonNode::class.java, orgId)
        return mapUserFromV2Response(response)
    }

    fun listUsers(
        orgId: String? = null,
        offset: Int = 0,
        limit: Int = 20,
        search: String? = null,
        state: String? = null,
        sortColumn: String? = null,
        sortAsc: Boolean = true,
    ): ZitadelUserList {
        val query = mutableMapOf<String, Any>(
            "offset" to offset.toString(),
            "limit" to limit,
            "asc" to sortAsc,
        )

        val queries = mutableListOf<Map<String, Any>>()
        queries.add(mapOf("typeQuery" to mapOf("type" to "TYPE_HUMAN")))

        if (!orgId.isNullOrBlank()) {
            queries.add(mapOf("organizationIdQuery" to mapOf("organizationId" to orgId)))
        }

        if (!search.isNullOrBlank()) {
            val method = "TEXT_QUERY_METHOD_CONTAINS_IGNORE_CASE"
            queries.add(
                mapOf(
                    "orQuery" to mapOf(
                        "queries" to listOf(
                            mapOf("emailQuery" to mapOf("emailAddress" to search, "method" to method)),
                            mapOf("displayNameQuery" to mapOf("displayName" to search, "method" to method)),
                            mapOf("firstNameQuery" to mapOf("firstName" to search, "method" to method)),
                            mapOf("lastNameQuery" to mapOf("lastName" to search, "method" to method)),
                        )
                    )
                )
            )
        }

        if (!state.isNullOrBlank()) {
            val zitadelState = if (state.equals("INACTIVE", ignoreCase = true))
                "USER_STATE_INACTIVE" else "USER_STATE_ACTIVE"
            queries.add(mapOf("stateQuery" to mapOf("state" to zitadelState)))
        }

        val body = mutableMapOf<String, Any>(
            "query" to query,
            "queries" to queries,
        )

        if (!sortColumn.isNullOrBlank()) {
            mapSortColumn(sortColumn)?.let { body["sortingColumn"] = it }
        }

        val response = apiPost("/v2/users", body, JsonNode::class.java, orgId)

        val results = response["result"] ?: objectMapper.createArrayNode()
        val totalCount = response["details"]?.get("totalResult")?.asLong() ?: 0L

        // Fetch grants to merge roles into user responses
        val userRolesMap = if (properties.projectId.isNotBlank()) {
            fetchAllUserGrants(orgId)
        } else {
            emptyMap()
        }

        val users = results.map { userNode ->
            val user = mapUserFromV2Result(userNode)
            user.copy(roles = userRolesMap[user.id] ?: emptyList())
        }

        return ZitadelUserList(
            users = users,
            totalCount = totalCount,
            offset = offset,
            limit = limit,
        )
    }

    fun deactivateUser(userId: String, orgId: String? = null) {
        apiPostNoContent("/v2/users/$userId/deactivate", emptyMap<String, Any>(), orgId)
    }

    fun reactivateUser(userId: String, orgId: String? = null) {
        apiPostNoContent("/v2/users/$userId/reactivate", emptyMap<String, Any>(), orgId)
    }

    fun sendPasswordResetEmail(userId: String) {
        apiPost(
            "/v2/users/$userId/password_reset",
            mapOf(
                "sendLink" to mapOf(
                    "notificationType" to "NOTIFICATION_TYPE_Email",
                )
            ),
            JsonNode::class.java
        )
    }

    // ==================== User Grants (Roles) ====================

    fun assignRoleToUser(orgId: String, projectId: String, userId: String, roles: List<String>) {
        apiPost(
            "/management/v1/users/$userId/grants",
            mapOf(
                "projectId" to projectId,
                "roleKeys" to roles,
            ),
            JsonNode::class.java,
            orgId
        )
    }

    fun updateUserRoles(orgId: String, userId: String, roles: List<String>) {
        require(properties.projectId.isNotBlank()) { "projectId must be configured to update user roles" }

        val grantId = findUserGrantId(userId, orgId)
        if (grantId != null) {
            apiPut(
                "/management/v1/users/$userId/grants/$grantId",
                mapOf("roleKeys" to roles),
                JsonNode::class.java,
                orgId
            )
        } else {
            assignRoleToUser(orgId, properties.projectId, userId, roles)
        }
    }

    fun searchUserGrants(
        orgId: String? = null,
        projectId: String? = null,
        userId: String? = null,
    ): List<UserGrant> {
        val queries = mutableListOf<Map<String, Any>>()
        if (!projectId.isNullOrBlank()) {
            queries.add(mapOf("projectIdQuery" to mapOf("projectId" to projectId)))
        }
        if (!userId.isNullOrBlank()) {
            queries.add(mapOf("userIdQuery" to mapOf("userId" to userId)))
        }

        val response = apiPost(
            "/management/v1/users/grants/_search",
            mapOf("queries" to queries),
            JsonNode::class.java,
            orgId
        )

        val results = response["result"] ?: return emptyList()
        return results.map { grant ->
            UserGrant(
                id = grant["id"]?.asText() ?: grant["userGrantId"]?.asText() ?: "",
                userId = grant["userId"]?.asText() ?: "",
                projectId = grant["projectId"]?.asText() ?: "",
                roleKeys = grant["roleKeys"]?.map { it.asText() } ?: emptyList(),
            )
        }
    }

    // ==================== Projects ====================

    fun createProject(orgId: String, name: String): ZitadelProject {
        val response = apiPost(
            "/management/v1/projects",
            mapOf("name" to name),
            JsonNode::class.java,
            orgId
        )
        return ZitadelProject(
            id = response["id"]?.asText() ?: "",
            name = name,
            state = response["details"]?.get("state")?.asText(),
            creationDate = response["details"]?.get("creationDate")?.asText(),
        )
    }

    fun addRoleToProject(orgId: String, projectId: String, roleKey: String, displayName: String) {
        addRolesToProject(orgId, projectId, listOf(ProjectRole(key = roleKey, displayName = displayName)))
    }

    fun addRolesToProject(orgId: String, projectId: String, roles: List<ProjectRole>) {
        apiPost(
            "/management/v1/projects/$projectId/roles/_bulk",
            mapOf(
                "roles" to roles.map { role ->
                    mutableMapOf<String, String>(
                        "key" to role.key,
                        "displayName" to role.displayName,
                    ).also { if (role.group.isNotBlank()) it["group"] = role.group }
                }
            ),
            JsonNode::class.java,
            orgId
        )
    }

    fun grantProjectToOrganization(orgId: String, projectId: String, grantedOrgId: String, roleKeys: List<String>) {
        apiPost(
            "/management/v1/projects/$projectId/grants",
            mapOf(
                "grantedOrgId" to grantedOrgId,
                "roleKeys" to roleKeys,
            ),
            JsonNode::class.java,
            orgId
        )
    }

    // ==================== Private Helpers ====================

    private fun findUserGrantId(userId: String, orgId: String?): String? {
        return try {
            val grants = searchUserGrants(
                orgId = orgId,
                projectId = properties.projectId,
                userId = userId,
            )
            grants.firstOrNull()?.id
        } catch (e: Exception) {
            log.warn("Failed to find grant for user {}: {}", userId, e.message)
            null
        }
    }

    private fun fetchAllUserGrants(orgId: String?): Map<String, List<String>> {
        return try {
            val grants = searchUserGrants(orgId = orgId, projectId = properties.projectId)
            grants.groupBy({ it.userId }, { it.roleKeys })
                .mapValues { (_, v) -> v.flatten().distinct() }
        } catch (e: Exception) {
            log.warn("Failed to fetch user grants: {}", e.message)
            emptyMap()
        }
    }

    private fun mapUserFromV2Response(response: JsonNode): ZitadelUser {
        val user = response["user"] ?: response
        return mapUserFromV2Result(user)
    }

    private fun mapUserFromV2Result(node: JsonNode): ZitadelUser {
        val userId = node["userId"]?.asText() ?: node["id"]?.asText() ?: ""
        val stateStr = node["state"]?.asText()

        // v2 nests human data under "type.human"
        val type = node["type"]
        val human = type?.get("human") ?: node["human"]
        val profile = human?.get("profile")
        val emailObj = human?.get("email")

        val email = emailObj?.get("email")?.asText()
        val firstName = profile?.get("givenName")?.asText()
        val lastName = profile?.get("familyName")?.asText()
        val username = node["userName"]?.asText() ?: node["username"]?.asText() ?: email

        val status = if (stateStr?.contains("INACTIVE") == true) "INACTIVE" else "ACTIVE"
        val creationDate = node["details"]?.get("creationDate")?.asText()

        return ZitadelUser(
            id = userId,
            username = username,
            email = email,
            firstName = firstName,
            lastName = lastName,
            state = status,
            creationDate = creationDate,
        )
    }

    private companion object {
        val SORT_COLUMN_MAP = mapOf(
            "email" to "USER_FIELD_NAME_EMAIL",
            "firstName" to "USER_FIELD_NAME_FIRST_NAME",
            "lastName" to "USER_FIELD_NAME_LAST_NAME",
            "createdAt" to "USER_FIELD_NAME_CREATION_DATE",
        )

        fun mapSortColumn(column: String): String? = SORT_COLUMN_MAP[column]
    }
}
