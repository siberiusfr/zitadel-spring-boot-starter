# Zitadel Spring Boot Starter

[![CI](https://github.com/siberiusfr/zitadel-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/siberiusfr/zitadel-spring-boot-starter/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/siberiusfr/zitadel-spring-boot-starter.svg)](https://jitpack.io/#siberiusfr/zitadel-spring-boot-starter)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A reusable Spring Boot starter that wraps the [Zitadel](https://zitadel.com/) IAM Management API, providing easy-to-use service methods for managing organizations, users, projects, and roles.

## Installation

### Gradle (Kotlin DSL)

Add the JitPack repository:

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}
```

Add the dependency:

```kotlin
dependencies {
    implementation("com.github.cyberious-tn:zitadel-spring-boot-starter:main-SNAPSHOT")
}
```

### Gradle (Groovy)

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.cyberious-tn:zitadel-spring-boot-starter:main-SNAPSHOT'
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
    <groupId>com.github.cyberious-tn</groupId>
    <artifactId>zitadel-spring-boot-starter</artifactId>
    <version>main-SNAPSHOT</version>
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

## Usage

Once configured, inject `ZitadelManagementService` into your Spring beans:

```kotlin
@Service
class MyService(private val zitadel: ZitadelManagementService) {

    fun setupTenant(name: String) {
        val org = zitadel.createOrganization(name)
        val project = zitadel.createProject(org.id, "My Project")
        zitadel.addRoleToProject(org.id, project.id, "admin", "Administrator")
    }
}
```

## Available Methods

### Organizations

| Method | API Endpoint |
|--------|-------------|
| `createOrganization(name)` | `POST /v2/organizations` |
| `getOrganization(orgId)` | `GET /management/v1/orgs/me` |
| `deactivateOrganization(orgId)` | `POST /admin/v1/orgs/{orgId}/_deactivate` |
| `reactivateOrganization(orgId)` | `POST /admin/v1/orgs/{orgId}/_reactivate` |

### Users

| Method | API Endpoint |
|--------|-------------|
| `createHumanUser(orgId, email, firstName, lastName, password?, isEmailVerified?)` | `POST /v2/users/human` |
| `createMachineUser(orgId, username, name)` | `POST /management/v1/users/machine` |
| `getUserById(userId)` | `GET /v2/users/{userId}` |
| `sendPasswordResetEmail(userId)` | `POST /v2/users/{userId}/password_reset` |

### Projects

| Method | API Endpoint |
|--------|-------------|
| `createProject(orgId, name)` | `POST /management/v1/projects` |
| `addRoleToProject(orgId, projectId, roleKey, displayName)` | `POST /management/v1/projects/{projectId}/roles/_bulk` |
| `addRolesToProject(orgId, projectId, roles)` | `POST /management/v1/projects/{projectId}/roles/_bulk` |
| `grantProjectToOrganization(orgId, projectId, grantedOrgId, roleKeys)` | `POST /management/v1/projects/{projectId}/grants` |
| `assignRoleToUser(orgId, projectId, userId, roles)` | `POST /management/v1/users/{userId}/grants` |

## Authentication

The starter uses JWT Profile authentication (service account). It automatically:

1. Parses the service account key JSON to extract credentials
2. Generates a signed JWT (RS256) for authentication
3. Exchanges the JWT for an OAuth2 access token
4. Caches the token and refreshes it before expiry

## Error Handling

API errors throw `ZitadelException` with:
- `message` - Error description
- `httpStatus` - HTTP status code
- `zitadelCode` - Zitadel-specific error code (when available)

## Requirements

- Java 21+
- Spring Boot 3.4.x+
