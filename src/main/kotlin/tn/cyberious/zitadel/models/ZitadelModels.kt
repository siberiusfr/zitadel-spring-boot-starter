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
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val state: String? = null,
    val creationDate: String? = null,
    val roles: List<String> = emptyList(),
)

data class ZitadelUserList(
    val users: List<ZitadelUser>,
    val totalCount: Long,
    val offset: Int,
    val limit: Int,
)

data class ZitadelProject(
    val id: String,
    val name: String,
    val state: String? = null,
    val creationDate: String? = null,
)

data class ProjectRole(
    val key: String,
    val displayName: String,
    val group: String = "",
)

data class UserGrant(
    val id: String,
    val userId: String,
    val projectId: String,
    val roleKeys: List<String> = emptyList(),
)
