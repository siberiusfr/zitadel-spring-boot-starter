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
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tn.cyberious.zitadel.config.ZitadelProperties
import tn.cyberious.zitadel.exception.ZitadelException
import tn.cyberious.zitadel.models.ProjectRole
import tn.cyberious.zitadel.models.ZitadelOrganization
import tn.cyberious.zitadel.models.ZitadelProject
import tn.cyberious.zitadel.models.ZitadelUser
import java.io.StringReader
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.*

class ZitadelManagementService(
    private val properties: ZitadelProperties,
) {
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val restClient: RestClient = RestClient.builder()
        .baseUrl(properties.domain)
        .build()

    private var cachedToken: String? = null
    private var tokenExpiry: Instant = Instant.MIN

    private val serviceAccountKey: JsonNode by lazy {
        objectMapper.readTree(properties.serviceAccountKeyJson)
    }

    private fun getAccessToken(): String {
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

        val tokenResponse = restClient.post()
            .uri("/oauth/v2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer" +
                    "&scope=openid+urn%3Azitadel%3Aiam%3Aorg%3Aproject%3Aid%3Azitadel%3Aaud" +
                    "&assertion=$assertion"
            )
            .retrieve()
            .onStatus({ it.isError }) { _, response ->
                val body = response.body.bufferedReader().readText()
                throw ZitadelException(
                    "Token exchange failed: $body",
                    response.statusCode.value()
                )
            }
            .body(JsonNode::class.java)!!

        cachedToken = tokenResponse["access_token"].asText()
        val expiresIn = tokenResponse["expires_in"]?.asLong() ?: 3600
        tokenExpiry = now.plusSeconds(expiresIn)

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

    // --- Organization ---

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
        apiPost("/management/v1/orgs/me/_deactivate", emptyMap<String, Any>(), JsonNode::class.java, orgId)
    }

    fun reactivateOrganization(orgId: String) {
        apiPost("/management/v1/orgs/me/_reactivate", emptyMap<String, Any>(), JsonNode::class.java, orgId)
    }

    // --- Users ---

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

    fun getUserById(userId: String): ZitadelUser {
        val response = apiGet("/v2/users/$userId", JsonNode::class.java)
        val user = response["user"] ?: response
        return ZitadelUser(
            id = user["userId"]?.asText() ?: user["id"]?.asText() ?: userId,
            username = user["userName"]?.asText() ?: user["username"]?.asText(),
            state = user["state"]?.asText(),
            creationDate = user["details"]?.get("creationDate")?.asText(),
        )
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

    // --- Projects (v1 - TODO: migrate to v2 when Zitadel provides v2 project endpoints) ---

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
}
