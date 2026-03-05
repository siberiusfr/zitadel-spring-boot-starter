package tn.cyberious.zitadel.integration

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import tn.cyberious.zitadel.config.ZitadelProperties
import tn.cyberious.zitadel.exception.ZitadelException
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

    private lateinit var machinekeyDir: java.nio.file.Path
    private var createdOrgId: String = ""
    private var createdHumanUserId: String = ""
    private var createdMachineUserId: String = ""
    private var createdProjectId: String = ""

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

        zitadel = GenericContainer("ghcr.io/zitadel/zitadel:latest")
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
            .waitingFor(
                Wait.forHttp("/debug/ready")
                    .forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(5))
            )

        zitadel.portBindings = listOf("$hostPort:8080")
        zitadel.start()

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

    // --- Error handling tests ---

    @Test
    @Order(10)
    fun `should throw ZitadelException for non-existent user`() {
        val exception = assertThrows<ZitadelException> {
            service.getUserById("non-existent-user-id-12345")
        }
        assertTrue(exception.httpStatus in 400..499)
        println("Got expected error: ${exception.message} (status: ${exception.httpStatus})")
    }
}
