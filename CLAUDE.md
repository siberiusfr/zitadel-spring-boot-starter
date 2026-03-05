# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

A Spring Boot starter library (not an application) that wraps Zitadel's IAM Management API. Published via JitPack as `com.github.siberiusfr:zitadel-spring-boot-starter`. Used by Wakil project.

## Build Commands

```bash
./gradlew build              # compile + unit tests (excludes integration)
./gradlew test               # unit tests only
./gradlew integrationTest    # integration tests (requires Docker)
./gradlew test --tests "tn.cyberious.zitadel.service.ZitadelManagementServiceTest"  # single test class
```

## Key Build Configuration

- Spring Boot plugin uses `apply false` (this is a library, not a bootable app)
- Uses `java-library` + `maven-publish` plugins for JitPack distribution
- Spring Boot BOM imported via `dependencyManagement` block
- No `@SpringBootApplication` class exists or should be created
- Releases via git tags (`git tag v0.x.0 && git push origin v0.x.0`), JitPack builds automatically

## Architecture

**Auto-configuration flow:** Consumer adds the dependency, sets `cyberious.zitadel.*` properties in their `application.yml`, and gets a `ZitadelManagementService` bean auto-configured. The bean is only created when either `cyberious.zitadel.service-account-key-json` or `cyberious.zitadel.personal-access-token` is present — apps can safely include the starter without providing credentials.

- `ZitadelAutoConfiguration` - entry point registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. The bean uses `@ConditionalOnExpression` checking that either `service-account-key-json` or `personal-access-token` is present, so the starter won't crash when config is absent.
- `ZitadelProperties` - binds `cyberious.zitadel.*` (domain, serviceAccountKeyJson, personalAccessToken, defaultOrganizationId)
- `ZitadelManagementService` - the main facade. Handles JWT Profile auth (RS256 via nimbus-jose-jwt + BouncyCastle PEM parsing), token caching with 60s refresh buffer, and all Zitadel API calls via Spring 6 `RestClient`.

**API version split:** Organization creation and user endpoints use Zitadel v2 API. Project, role, grant, and org lifecycle endpoints use v1 (`/management/v1/...`). Org-scoped calls pass `x-zitadel-orgid` header.

**Authentication:** Two modes supported. PAT mode: if `personalAccessToken` is set, it's used directly as Bearer token (no refresh). JWT Profile mode: service account key JSON is parsed to extract userId, keyId, privateKey; a signed JWT is exchanged for an OAuth2 access token at `/oauth/v2/token`. PAT takes priority if both are configured.

## Integration Tests

Integration tests use Testcontainers with PostgreSQL + Zitadel (pinned to `v2.71.6`).

**On Windows:** Requires Docker Desktop with TCP endpoint exposed on port 2375. `DockerApiVersionFix` patches Testcontainers via reflection for Docker Engine 29.x compatibility. This patch is skipped on CI (detected via `CI` env var).

**On CI (GitHub Actions):** Docker available natively via Unix socket. No special configuration needed.

The test spins up PostgreSQL + Zitadel containers, creates a service account via `start-from-init` steps config, and runs 16 integration tests covering all service methods.

## CI/CD

- `.github/workflows/ci.yml` - Build + unit tests, then integration tests on push to main and PRs
- `.github/workflows/release.yml` - Full build + tests + GitHub Release on tag push (`v*`)
- `jitpack.yml` - Configures JDK 21 for JitPack builds

## Zitadel API Gotchas

- Human users: `POST /v2/users/human` (not `/v2/users`)
- Machine users: `POST /management/v1/users/machine` (no v2 endpoint yet)
- Get org: `GET /management/v1/orgs/me` with `x-zitadel-orgid` header (not `/v2/organizations/{id}`)
- Deactivate/reactivate org: `POST /management/v1/orgs/me/_deactivate` with `x-zitadel-orgid` header
- Bulk roles: `POST /management/v1/projects/{id}/roles/_bulk` with `roles` array wrapper
- Project grants: `POST /management/v1/projects/{id}/grants` with owner org in `x-zitadel-orgid` header
- PEM keys from Zitadel are PKCS#1 format (`BEGIN RSA PRIVATE KEY`), requiring BouncyCastle to parse
- Password reset: `POST /v2/users/{userId}/password_reset` with `sendLink.notificationType`
