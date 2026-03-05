# Zitadel Spring Boot Starter

[![CI](https://github.com/siberiusfr/zitadel-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/siberiusfr/zitadel-spring-boot-starter/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/siberiusfr/zitadel-spring-boot-starter.svg)](https://jitpack.io/#siberiusfr/zitadel-spring-boot-starter)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A Spring Boot starter that wraps the [Zitadel](https://zitadel.com/) IAM Management API, providing easy-to-use service methods for managing organizations, users, projects, roles, and project grants.

## Installation

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.siberiusfr:zitadel-spring-boot-starter:v0.2.1")
}
```

### Gradle (Groovy)

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.siberiusfr:zitadel-spring-boot-starter:v0.2.1'
}
```

### Maven

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.siberiusfr</groupId>
    <artifactId>zitadel-spring-boot-starter</artifactId>
    <version>v0.2.1</version>
</dependency>
```

## Configuration

Add the following to your `application.yml`:

```yaml
cyberious:
  zitadel:
    domain: https://your-instance.zitadel.cloud
    service-account-key-json: |
      {
        "type": "serviceaccount",
        "keyId": "...",
        "key": "-----BEGIN RSA PRIVATE KEY-----\n...\n-----END RSA PRIVATE KEY-----",
        "userId": "..."
      }
    default-organization-id: "optional-org-id"
```

> **Note:** The `ZitadelManagementService` bean is only created when `cyberious.zitadel.service-account-key-json` is present. You can safely include this starter as a dependency without providing credentials — the auto-configuration will simply be skipped.

## Usage

Once configured, inject `ZitadelManagementService` into your Spring beans:

```kotlin
@Service
class TenantService(private val zitadel: ZitadelManagementService) {

    fun setupTenant(name: String): String {
        // Create organization
        val org = zitadel.createOrganization(name)

        // Create project with roles
        val project = zitadel.createProject(org.id, "MyApp")
        zitadel.addRolesToProject(
            org.id, project.id,
            listOf(
                ProjectRole(key = "admin", displayName = "Administrator", group = "management"),
                ProjectRole(key = "user", displayName = "User", group = "default"),
            )
        )

        // Create user with password
        val user = zitadel.createHumanUser(
            orgId = org.id,
            email = "admin@$name.com",
            firstName = "Admin",
            lastName = name,
            password = "InitialP@ss1",
        )

        // Assign role
        zitadel.assignRoleToUser(org.id, project.id, user.id, listOf("admin"))

        return org.id
    }

    fun disableTenant(orgId: String) {
        zitadel.deactivateOrganization(orgId)
    }

    fun grantProjectAccess(ownerOrgId: String, projectId: String, partnerOrgId: String) {
        zitadel.grantProjectToOrganization(ownerOrgId, projectId, partnerOrgId, listOf("user"))
    }
}
```

## Available Methods

### Organizations

| Method | Description | API Endpoint |
|--------|-------------|-------------|
| `createOrganization(name)` | Create a new organization | `POST /v2/organizations` |
| `getOrganization(orgId)` | Get organization details | `GET /management/v1/orgs/me` |
| `deactivateOrganization(orgId)` | Deactivate an organization | `POST /management/v1/orgs/me/_deactivate` |
| `reactivateOrganization(orgId)` | Reactivate an organization | `POST /management/v1/orgs/me/_reactivate` |

### Users

| Method | Description | API Endpoint |
|--------|-------------|-------------|
| `createHumanUser(orgId, email, firstName, lastName, password?, isEmailVerified?)` | Create a human user | `POST /v2/users/human` |
| `createMachineUser(orgId, username, name)` | Create a machine (service) user | `POST /management/v1/users/machine` |
| `getUserById(userId)` | Get user by ID | `GET /v2/users/{userId}` |
| `sendPasswordResetEmail(userId)` | Send password reset email | `POST /v2/users/{userId}/password_reset` |

### Projects and Roles

| Method | Description | API Endpoint |
|--------|-------------|-------------|
| `createProject(orgId, name)` | Create a project | `POST /management/v1/projects` |
| `addRoleToProject(orgId, projectId, roleKey, displayName)` | Add a single role | `POST /management/v1/projects/{id}/roles/_bulk` |
| `addRolesToProject(orgId, projectId, roles)` | Add multiple roles at once | `POST /management/v1/projects/{id}/roles/_bulk` |
| `grantProjectToOrganization(orgId, projectId, grantedOrgId, roleKeys)` | Grant project access to another org | `POST /management/v1/projects/{id}/grants` |
| `assignRoleToUser(orgId, projectId, userId, roles)` | Assign roles to a user | `POST /management/v1/users/{userId}/grants` |

### Models

```kotlin
data class ZitadelOrganization(val id: String, val name: String, val state: String?, val creationDate: String?)
data class ZitadelUser(val id: String, val username: String?, val state: String?, val creationDate: String?)
data class ZitadelProject(val id: String, val name: String, val state: String?, val creationDate: String?)
data class ProjectRole(val key: String, val displayName: String, val group: String = "")
```

## Authentication

The starter uses JWT Profile authentication (service account). It automatically:

1. Parses the service account key JSON to extract credentials
2. Generates a signed JWT (RS256) for authentication
3. Exchanges the JWT for an OAuth2 access token via `/oauth/v2/token`
4. Caches the token and refreshes it before expiry (60s buffer)

## Error Handling

API errors throw `ZitadelException` with:
- `message` - Error description from Zitadel
- `httpStatus` - HTTP status code (e.g., 404, 409, 500)
- `zitadelCode` - Zitadel-specific error code (when available)

```kotlin
try {
    zitadel.getUserById("non-existent-id")
} catch (e: ZitadelException) {
    println("Status: ${e.httpStatus}, Code: ${e.zitadelCode}, Message: ${e.message}")
}
```

## Requirements

- Java 21+
- Spring Boot 3.4.x+
- A Zitadel instance with a service account key (JWT profile)

## License

[MIT](LICENSE)
