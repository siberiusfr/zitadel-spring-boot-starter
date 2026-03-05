package tn.cyberious.zitadel.models

data class ZitadelOrganization(
    val id: String,
    val name: String,
    val state: String? = null,
    val creationDate: String? = null,
)

data class ZitadelUser(
    val id: String,
    val username: String? = null,
    val state: String? = null,
    val creationDate: String? = null,
)

data class ZitadelProject(
    val id: String,
    val name: String,
    val state: String? = null,
    val creationDate: String? = null,
)
