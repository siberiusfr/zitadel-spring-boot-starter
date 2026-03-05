package tn.cyberious.zitadel.integration

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import org.slf4j.LoggerFactory
import tn.cyberious.zitadel.config.ZitadelProperties
import tn.cyberious.zitadel.exception.ZitadelException
import tn.cyberious.zitadel.models.ProjectRole
import tn.cyberious.zitadel.service.ZitadelManagementService
import java.net.ServerSocket
import java.nio.file.Files
import java.time.Duration

@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZitadelManagementServiceIntegrationTest {

    private lateinit var service: ZitadelManagementService
    private lateinit var network: Network
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var zitadel: GenericContainer<*>

    private val log = LoggerFactory.getLogger(ZitadelManagementServiceIntegrationTest::class.java)
    private lateinit var machinekeyDir: java.nio.file.Path
    private var createdOrgId: String = ""
    private var createdHumanUserId: String = ""
    private var createdMachineUserId: String = ""
    private var createdProjectId: String = ""
    private var createdGrantedOrgId: String = ""
    private var createdUserWithPasswordId: String = ""

    companion object {
        @JvmStatic
        @BeforeAll
        fun initDocker() {
            // Fix for Docker Engine 29.x + Testcontainers incompatibility (Windows TCP mode)
            // Skip on CI / Linux where Testcontainers works natively
            if (System.getenv("CI") == null) {
                DockerApiVersionFix.patchTestcontainers()
            }
        }
    }

    @BeforeAll
    fun setup() {
        network = Network.newNetwork()

        postgres = PostgreSQLContainer("postgres:16-alpine")
            .withNetwork(network)
            .withNetworkAliases("db")
            .withDatabaseName("zitadel")
            .withUsername("zitadel")
            .withPassword("zitadel")

        postgres.start()

        val hostPort = findFreePort()
        machinekeyDir = Files.createTempDirectory("zitadel-machinekey")

        zitadel = GenericContainer("ghcr.io/zitadel/zitadel:v2.71.6")
            .withNetwork(network)
            .withExposedPorts(8080)
            .dependsOn(postgres)
            .withEnv("ZITADEL_DATABASE_POSTGRES_HOST", "db")
            .withEnv("ZITADEL_DATABASE_POSTGRES_PORT", "5432")
            .withEnv("ZITADEL_DATABASE_POSTGRES_DATABASE", "zitadel")
            .withEnv("ZITADEL_DATABASE_POSTGRES_USER_USERNAME", "zitadel")
            .withEnv("ZITADEL_DATABASE_POSTGRES_USER_PASSWORD", "zitadel")
            .withEnv("ZITADEL_DATABASE_POSTGRES_USER_SSL_MODE", "disable")
            .withEnv("ZITADEL_DATABASE_POSTGRES_ADMIN_USERNAME", "zitadel")
            .withEnv("ZITADEL_DATABASE_POSTGRES_ADMIN_PASSWORD", "zitadel")
            .withEnv("ZITADEL_DATABASE_POSTGRES_ADMIN_SSL_MODE", "disable")
            .withEnv("ZITADEL_EXTERNALSECURE", "false")
            .withEnv("ZITADEL_EXTERNALPORT", hostPort.toString())
            .withEnv("ZITADEL_EXTERNALDOMAIN", "localhost")
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("zitadel-steps.yaml"),
                "/zitadel-steps.yaml"
            )
            .withFileSystemBind(
                machinekeyDir.toAbsolutePath().toString(),
                "/machinekey",
                org.testcontainers.containers.BindMode.READ_WRITE
            )
            .withCommand(
                "start-from-init",
                "--masterkey", "MasterkeyNeedsToHave32Characters",
                "--tlsMode", "disabled",
                "--steps", "/zitadel-steps.yaml"
            )
            .withLogConsumer(Slf4jLogConsumer(log).withPrefix("ZITADEL"))
            .waitingFor(
                Wait.forHttp("/debug/ready")
                    .forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(5))
            )

        zitadel.portBindings = listOf("$hostPort:8080")
        try {
            zitadel.start()
        } catch (e: Exception) {
            println("=== Zitadel container logs ===")
            println(zitadel.logs)
            println("=== End Zitadel container logs ===")
            throw e
        }

        // Wait for Zitadel to fully initialize (gRPC takes longer than REST health check)
        waitForZitadelApi(hostPort)

        val machineKeyFile = machinekeyDir.resolve("zitadel-admin-sa.json")
        val machineKeyJson = Files.readString(machineKeyFile)

        val domain = "http://localhost:$hostPort"

        val properties = ZitadelProperties(
            domain = domain,
            serviceAccountKeyJson = machineKeyJson,
        )

        service = ZitadelManagementService(properties)

        println("Zitadel started at $domain")
        println("Machine key loaded: ${machineKeyJson.take(80)}...")
    }

    @AfterAll
    fun teardown() {
        if (::zitadel.isInitialized) zitadel.stop()
        if (::postgres.isInitialized) postgres.stop()
        if (::network.isInitialized) network.close()
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun waitForZitadelApi(port: Int) {
        val maxRetries = 30
        val retryDelay = 2000L
        for (i in 1..maxRetries) {
            try {
                val url = java.net.URI("http://localhost:$port/management/v1/healthz").toURL()
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                if (conn.responseCode == 200) {
                    println("Zitadel management API ready after $i attempts")
                    // Extra grace period for gRPC services to fully stabilize
                    Thread.sleep(5000)
                    return
                }
            } catch (_: Exception) {
                // not ready yet
            }
            Thread.sleep(retryDelay)
        }
        throw IllegalStateException("Zitadel API did not become ready in time")
    }

    // --- Organization tests ---

    @Test
    @Order(1)
    fun `should create organization`() {
        val org = service.createOrganization("IntegrationTestOrg")
        assertNotNull(org.id)
        assertTrue(org.id.isNotBlank(), "Organization ID should not be blank")
        createdOrgId = org.id
        println("Created organization: ${org.id}")
    }

    @Test
    @Order(2)
    fun `should get organization`() {
        assertTrue(createdOrgId.isNotBlank(), "Organization must be created first")
        val org = service.getOrganization(createdOrgId)
        assertEquals(createdOrgId, org.id)
        assertEquals("IntegrationTestOrg", org.name)
        println("Retrieved organization: ${org.name} (${org.id})")
    }

    // --- Human User tests ---

    @Test
    @Order(3)
    fun `should create human user`() {
        assertTrue(createdOrgId.isNotBlank(), "Organization must be created first")
        val user = service.createHumanUser(
            orgId = createdOrgId,
            email = "john.doe@integration-test.com",
            firstName = "John",
            lastName = "Doe"
        )
        assertNotNull(user.id)
        assertTrue(user.id.isNotBlank(), "User ID should not be blank")
        assertEquals("john.doe@integration-test.com", user.email)
        assertEquals("John", user.firstName)
        assertEquals("Doe", user.lastName)
        createdHumanUserId = user.id
        println("Created human user: ${user.id}")
    }

    @Test
    @Order(4)
    fun `should get human user by id`() {
        assertTrue(createdHumanUserId.isNotBlank(), "Human user must be created first")
        val user = service.getUserById(createdHumanUserId)
        assertEquals(createdHumanUserId, user.id)
        println("Retrieved user: ${user.username} (${user.id})")
    }

    // --- Machine User tests ---

    @Test
    @Order(5)
    fun `should create machine user`() {
        assertTrue(createdOrgId.isNotBlank(), "Organization must be created first")
        val user = service.createMachineUser(
            orgId = createdOrgId,
            username = "integration-test-machine",
            name = "Integration Test Machine"
        )
        assertNotNull(user.id)
        assertTrue(user.id.isNotBlank(), "Machine user ID should not be blank")
        createdMachineUserId = user.id
        println("Created machine user: ${user.id}")
    }

    @Test
    @Order(6)
    fun `should get machine user by id`() {
        assertTrue(createdMachineUserId.isNotBlank(), "Machine user must be created first")
        val user = service.getUserById(createdMachineUserId)
        assertEquals(createdMachineUserId, user.id)
        println("Retrieved machine user: ${user.username} (${user.id})")
    }

    // --- Project tests ---

    @Test
    @Order(7)
    fun `should create project`() {
        assertTrue(createdOrgId.isNotBlank(), "Organization must be created first")
        val project = service.createProject(
            orgId = createdOrgId,
            name = "IntegrationTestProject"
        )
        assertNotNull(project.id)
        assertTrue(project.id.isNotBlank(), "Project ID should not be blank")
        createdProjectId = project.id
        println("Created project: ${project.id}")
    }

    // --- Role tests ---

    @Test
    @Order(8)
    fun `should add role to project`() {
        assertTrue(createdOrgId.isNotBlank(), "Organization must be created first")
        assertTrue(createdProjectId.isNotBlank(), "Project must be created first")
        assertDoesNotThrow {
            service.addRoleToProject(
                orgId = createdOrgId,
                projectId = createdProjectId,
                roleKey = "admin",
                displayName = "Administrator"
            )
        }
        println("Added role 'admin' to project $createdProjectId")
    }

    @Test
    @Order(9)
    fun `should add roles to project in bulk`() {
        assertTrue(createdOrgId.isNotBlank(), "Organization must be created first")
        assertTrue(createdProjectId.isNotBlank(), "Project must be created first")
        assertDoesNotThrow {
            service.addRolesToProject(
                orgId = createdOrgId,
                projectId = createdProjectId,
                roles = listOf(
                    ProjectRole(key = "syndic", displayName = "Syndic", group = "management"),
                    ProjectRole(key = "resident", displayName = "Resident", group = "users"),
                )
            )
        }
        println("Added bulk roles to project $createdProjectId")
    }

    @Test
    @Order(10)
    fun `should assign role to user`() {
        assertTrue(createdOrgId.isNotBlank(), "Organization must be created first")
        assertTrue(createdProjectId.isNotBlank(), "Project must be created first")
        assertTrue(createdHumanUserId.isNotBlank(), "Human user must be created first")
        assertDoesNotThrow {
            service.assignRoleToUser(
                orgId = createdOrgId,
                projectId = createdProjectId,
                userId = createdHumanUserId,
                roles = listOf("admin")
            )
        }
        println("Assigned role 'admin' to user $createdHumanUserId")
    }

    // --- Search user grants ---

    @Test
    @Order(11)
    fun `should search user grants`() {
        assertTrue(createdOrgId.isNotBlank(), "Organization must be created first")
        assertTrue(createdProjectId.isNotBlank(), "Project must be created first")
        assertTrue(createdHumanUserId.isNotBlank(), "Human user must be created first")
        val grants = service.searchUserGrants(
            orgId = createdOrgId,
            projectId = createdProjectId,
            userId = createdHumanUserId,
        )
        assertTrue(grants.isNotEmpty(), "Should find at least one grant")
        assertEquals(createdHumanUserId, grants[0].userId)
        assertTrue(grants[0].roleKeys.contains("admin"), "Grant should contain admin role")
        println("Found ${grants.size} grants for user $createdHumanUserId: ${grants[0].roleKeys}")
    }

    // --- Update user roles ---

    @Test
    @Order(12)
    fun `should update user roles`() {
        assertTrue(createdOrgId.isNotBlank(), "Organization must be created first")
        assertTrue(createdProjectId.isNotBlank(), "Project must be created first")
        assertTrue(createdHumanUserId.isNotBlank(), "Human user must be created first")

        // Need to set projectId on properties for updateUserRoles to work
        // Re-create service with projectId set
        val props = ZitadelProperties(
            domain = service.let {
                // Get domain from existing service by getting the access token endpoint
                val field = it.javaClass.getDeclaredField("properties")
                field.isAccessible = true
                (field.get(it) as ZitadelProperties).domain
            },
            serviceAccountKeyJson = service.let {
                val field = it.javaClass.getDeclaredField("properties")
                field.isAccessible = true
                (field.get(it) as ZitadelProperties).serviceAccountKeyJson
            },
            projectId = createdProjectId,
        )
        val serviceWithProject = ZitadelManagementService(props)

        assertDoesNotThrow {
            serviceWithProject.updateUserRoles(
                orgId = createdOrgId,
                userId = createdHumanUserId,
                roles = listOf("admin", "syndic"),
            )
        }

        // Verify roles were updated
        val grants = serviceWithProject.searchUserGrants(
            orgId = createdOrgId,
            projectId = createdProjectId,
            userId = createdHumanUserId,
        )
        assertTrue(grants.isNotEmpty(), "Should find grant after update")
        assertTrue(grants[0].roleKeys.containsAll(listOf("admin", "syndic")), "Grant should contain updated roles")
        println("Updated roles for user $createdHumanUserId: ${grants[0].roleKeys}")
    }

    // --- List users ---

    @Test
    @Order(13)
    fun `should list users`() {
        assertTrue(createdOrgId.isNotBlank(), "Organization must be created first")
        val result = service.listUsers(
            orgId = createdOrgId,
            offset = 0,
            limit = 10,
        )
        assertTrue(result.users.isNotEmpty(), "Should find at least one user")
        assertTrue(result.totalCount > 0, "Total count should be > 0")
        println("Listed ${result.users.size} users (total: ${result.totalCount})")
    }

    @Test
    @Order(14)
    fun `should list users with search`() {
        assertTrue(createdOrgId.isNotBlank(), "Organization must be created first")
        val result = service.listUsers(
            orgId = createdOrgId,
            search = "john",
        )
        assertTrue(result.users.isNotEmpty(), "Should find user matching 'john'")
        assertTrue(
            result.users.any { it.email?.contains("john", ignoreCase = true) == true },
            "Should find john.doe user"
        )
        println("Search 'john' found ${result.users.size} users")
    }

    // --- Project grant tests ---

    @Test
    @Order(15)
    fun `should grant project to another organization`() {
        assertTrue(createdOrgId.isNotBlank(), "Organization must be created first")
        assertTrue(createdProjectId.isNotBlank(), "Project must be created first")
        val grantedOrg = service.createOrganization("GrantedTestOrg")
        createdGrantedOrgId = grantedOrg.id
        assertDoesNotThrow {
            service.grantProjectToOrganization(
                orgId = createdOrgId,
                projectId = createdProjectId,
                grantedOrgId = createdGrantedOrgId,
                roleKeys = listOf("admin", "syndic")
            )
        }
        println("Granted project $createdProjectId to org $createdGrantedOrgId")
    }

    // --- User lifecycle tests ---

    @Test
    @Order(16)
    fun `should deactivate user`() {
        assertTrue(createdHumanUserId.isNotBlank(), "Human user must be created first")
        assertDoesNotThrow {
            service.deactivateUser(createdHumanUserId)
        }
        val user = service.getUserById(createdHumanUserId)
        assertEquals("INACTIVE", user.state)
        println("Deactivated user $createdHumanUserId")
    }

    @Test
    @Order(17)
    fun `should reactivate user`() {
        assertTrue(createdHumanUserId.isNotBlank(), "Human user must be created first")
        assertDoesNotThrow {
            service.reactivateUser(createdHumanUserId)
        }
        val user = service.getUserById(createdHumanUserId)
        assertEquals("ACTIVE", user.state)
        println("Reactivated user $createdHumanUserId")
    }

    // --- Organization lifecycle tests ---

    @Test
    @Order(18)
    fun `should deactivate organization`() {
        assertTrue(createdGrantedOrgId.isNotBlank(), "Granted org must be created first")
        assertDoesNotThrow {
            service.deactivateOrganization(createdGrantedOrgId)
        }
        println("Deactivated organization $createdGrantedOrgId")
    }

    @Test
    @Order(19)
    fun `should reactivate organization`() {
        assertTrue(createdGrantedOrgId.isNotBlank(), "Granted org must be created first")
        assertDoesNotThrow {
            service.reactivateOrganization(createdGrantedOrgId)
        }
        println("Reactivated organization $createdGrantedOrgId")
    }

    // --- User with password tests ---

    @Test
    @Order(20)
    fun `should create human user with password`() {
        assertTrue(createdOrgId.isNotBlank(), "Organization must be created first")
        val user = service.createHumanUser(
            orgId = createdOrgId,
            email = "jane.doe@integration-test.com",
            firstName = "Jane",
            lastName = "Doe",
            password = "SecureP@ssw0rd!",
            isEmailVerified = true
        )
        assertNotNull(user.id)
        assertTrue(user.id.isNotBlank(), "User ID should not be blank")
        createdUserWithPasswordId = user.id
        println("Created human user with password: ${user.id}")
    }

    @Test
    @Order(21)
    fun `should send password reset email`() {
        assertTrue(createdUserWithPasswordId.isNotBlank(), "User with password must be created first")
        assertDoesNotThrow {
            service.sendPasswordResetEmail(createdUserWithPasswordId)
        }
        println("Sent password reset email to user $createdUserWithPasswordId")
    }

    // --- Error handling tests ---

    @Test
    @Order(30)
    fun `should throw ZitadelException for non-existent user`() {
        val exception = assertThrows<ZitadelException> {
            service.getUserById("non-existent-user-id-12345")
        }
        assertTrue(exception.httpStatus in 400..499)
        println("Got expected error: ${exception.message} (status: ${exception.httpStatus})")
    }
}
