package tn.cyberious.zitadel.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cyberious.zitadel")
data class ZitadelProperties(
    val domain: String = "",
    val serviceAccountKeyJson: String = "",
    val personalAccessToken: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val projectId: String = "",
    val defaultOrganizationId: String? = null,
)
