# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

A Spring Boot starter library (not an application) that wraps Zitadel's IAM Management API. Published via JitPack as `com.github.cyberious-tn:zitadel-spring-boot-starter`. Used by SyndicFlow and COMPTA projects.

## Build Commands

```bash
./gradlew build              # compile + unit tests (excludes integration)
./gradlew test               # unit tests only
./gradlew integrationTest    # integration tests (requires Docker TCP on port 2375)
./gradlew test --tests "tn.cyberious.zitadel.service.ZitadelManagementServiceTest"  # single test class
```

## Key Build Configuration

- Spring Boot plugin uses `apply false` (this is a library, not a bootable app)
- Uses `java-library` + `maven-publish` plugins for JitPack distribution
- Spring Boot BOM imported via `dependencyManagement` block
- No `@SpringBootApplication` class exists or should be created

## Architecture

**Auto-configuration flow:** Consumer adds the dependency, sets `cyberious.zitadel.domain` in their `application.yml`, and gets a `ZitadelManagementService` bean auto-configured.

- `ZitadelAutoConfiguration` - entry point registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Activates only when `cyberious.zitadel.domain` property is set.
- `ZitadelProperties` - binds `cyberious.zitadel.*` (domain, serviceAccountKeyJson, defaultOrganizationId)
- `ZitadelManagementService` - the main facade. Handles JWT Profile auth (RS256 via nimbus-jose-jwt + BouncyCastle PEM parsing), token caching with 60s refresh buffer, and all Zitadel API calls via Spring 6 `RestClient`.

**API version split:** Organization and user endpoints use Zitadel v2 API. Project, role, and user grant endpoints use v1 (`/management/v1/...`) with TODO comments for future migration. Org-scoped calls pass `x-zitadel-orgid` header.

**Authentication:** Service account key JSON is parsed to extract userId, keyId, privateKey. A signed JWT is exchanged for an OAuth2 access token at `/oauth/v2/token` using `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer`.

## Integration Tests

Integration tests require Docker Desktop with **TCP endpoint exposed on port 2375** (Docker Desktop Settings > General > "Expose daemon on tcp://localhost:2375 without TLS"). This is needed because Testcontainers + docker-java has a compatibility bug with Docker Engine 29.x API version 1.44+.

`DockerApiVersionFix` patches Testcontainers' `DockerClientFactory` via reflection to work around this. If Docker Desktop is updated and the bug is fixed upstream, this workaround can be removed.

The integration test spins up PostgreSQL + Zitadel containers, creates a service account via Zitadel's `start-from-init` steps config, and tests all service methods against the real API. IPv6 is disabled in the Zitadel container (via sysctl) to prevent internal gRPC connection failures.

## Zitadel API Gotchas

- Human users: `POST /v2/users/human` (not `/v2/users`)
- Machine users: `POST /management/v1/users/machine` (no v2 endpoint yet)
- Get org: `GET /management/v1/orgs/me` with `x-zitadel-orgid` header (not `/v2/organizations/{id}`)
- Bulk roles: `POST /management/v1/projects/{id}/roles/_bulk` with `roles` array wrapper
- PEM keys from Zitadel are PKCS#1 format (`BEGIN RSA PRIVATE KEY`), requiring BouncyCastle to parse
