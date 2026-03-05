# Zitadel Spring Boot Starter

[![CI](https://github.com/siberiusfr/zitadel-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/siberiusfr/zitadel-spring-boot-starter/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/siberiusfr/zitadel-spring-boot-starter.svg)](https://jitpack.io/#siberiusfr/zitadel-spring-boot-starter)
[![License](https://img.shields.io/github/license/siberiusfr/zitadel-spring-boot-starter)](LICENSE)

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

| Method | API Endpoint | Version |
|--------|-------------|---------|
| `createOrganization(name)` | `POST /v2/organizations` | v2 |
| `getOrganization(orgId)` | `GET /v2/organizations/{orgId}` | v2 |
| `createHumanUser(orgId, email, firstName, lastName)` | `POST /v2/users` | v2 |
| `createMachineUser(orgId, username, name)` | `POST /v2/users` | v2 |
| `getUserById(userId)` | `GET /v2/users/{userId}` | v2 |
| `createProject(orgId, name)` | `POST /management/v1/projects` | v1* |
| `addRoleToProject(orgId, projectId, roleKey, displayName)` | `POST /management/v1/projects/{projectId}/roles` | v1* |
| `assignRoleToUser(orgId, projectId, userId, roles)` | `POST /management/v1/users/{userId}/grants` | v1* |

\* These methods use the Zitadel v1 Management API because v2 equivalents are not yet available. They will be migrated to v2 when Zitadel provides those endpoints.

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
