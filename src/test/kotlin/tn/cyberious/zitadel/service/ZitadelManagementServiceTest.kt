package tn.cyberious.zitadel.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import tn.cyberious.zitadel.config.ZitadelProperties

class ZitadelManagementServiceTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    @Test
    fun `properties should bind correctly`() {
        val props = ZitadelProperties(
            domain = "https://test.zitadel.cloud",
            serviceAccountKeyJson = """{"userId":"u1","keyId":"k1","key":"-----BEGIN RSA PRIVATE KEY-----\ntest\n-----END RSA PRIVATE KEY-----"}""",
            defaultOrganizationId = "org-123"
        )
        assertEquals("https://test.zitadel.cloud", props.domain)
        assertEquals("org-123", props.defaultOrganizationId)
        assertNotNull(props.serviceAccountKeyJson)
    }

    @Test
    fun `properties should bind client credentials`() {
        val props = ZitadelProperties(
            domain = "https://test.zitadel.cloud",
            clientId = "my-client-id",
            clientSecret = "my-client-secret",
            projectId = "project-123",
        )
        assertEquals("my-client-id", props.clientId)
        assertEquals("my-client-secret", props.clientSecret)
        assertEquals("project-123", props.projectId)
    }

    @Test
    fun `service account key json should be parseable`() {
        val json = """
            {
                "type": "serviceaccount",
                "keyId": "key-123",
                "key": "-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END RSA PRIVATE KEY-----",
                "userId": "user-456"
            }
        """.trimIndent()

        val node = objectMapper.readTree(json)
        assertEquals("user-456", node["userId"].asText())
        assertEquals("key-123", node["keyId"].asText())
        assertNotNull(node["key"].asText())
    }

    @Test
    fun `ZitadelProperties default values`() {
        val props = ZitadelProperties()
        assertEquals("", props.domain)
        assertEquals("", props.serviceAccountKeyJson)
        assertEquals("", props.personalAccessToken)
        assertEquals("", props.clientId)
        assertEquals("", props.clientSecret)
        assertEquals("", props.projectId)
        assertEquals(null, props.defaultOrganizationId)
    }
}
