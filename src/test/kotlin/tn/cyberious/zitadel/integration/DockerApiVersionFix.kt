package tn.cyberious.zitadel.integration

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient
import org.testcontainers.DockerClientFactory
import org.testcontainers.dockerclient.DockerClientProviderStrategy
import org.testcontainers.dockerclient.TransportConfig
import java.net.URI

/**
 * Workaround for Docker Engine 29.x (API >= 1.44) incompatibility with
 * Testcontainers + docker-java's default config builder which fails to
 * read DOCKER_API_VERSION from the environment.
 */
object DockerApiVersionFix {

    private const val MIN_API_VERSION = "1.44"

    fun createDockerClient(): DockerClient {
        val envHost = System.getenv("DOCKER_HOST") ?: "tcp://localhost:2375"
        val envApiVersion = System.getenv("DOCKER_API_VERSION") ?: MIN_API_VERSION

        val config = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(envHost)
            .withApiVersion(envApiVersion)
            .build()

        val httpClient = ZerodepDockerHttpClient.Builder()
            .dockerHost(config.dockerHost)
            .sslConfig(config.sslConfig)
            .build()

        return DockerClientImpl.getInstance(config, httpClient)
    }

    fun patchTestcontainers() {
        val client = createDockerClient()

        // Verify it works
        val info = client.infoCmd().exec()
        require(info.osType?.isNotBlank() == true) {
            "Docker info returned empty OSType - Docker may not be running"
        }
        println("DockerApiVersionFix: Docker ${info.serverVersion} (${info.osType}) connected")

        val factory = DockerClientFactory.instance()
        val dockerHost = System.getenv("DOCKER_HOST") ?: "tcp://localhost:2375"

        // Create a dummy strategy that wraps our working client
        val strategy = object : DockerClientProviderStrategy() {
            override fun getDescription(): String = "Fixed API version strategy"
            override fun getTransportConfig(): TransportConfig {
                return TransportConfig.builder()
                    .dockerHost(URI.create(dockerHost))
                    .build()
            }

            override fun test(): Boolean = true
        }

        // Patch factory fields via reflection
        setFieldValue(factory, "client", client)
        setFieldValue(factory, "strategy", strategy)
        setFieldValue(factory, "cachedClientFailure", null)
        setFieldValue(factory, "activeApiVersion", info.serverVersion ?: "1.44")

        // Reset the fail-fast flag on DockerClientProviderStrategy
        try {
            val strategyClass = DockerClientProviderStrategy::class.java
            val failFastField = strategyClass.getDeclaredField("FAIL_FAST_ALWAYS")
            failFastField.isAccessible = true
            val atomicBool = failFastField.get(null) as java.util.concurrent.atomic.AtomicBoolean
            atomicBool.set(false)
        } catch (e: Exception) {
            println("Warning: Could not reset FAIL_FAST_ALWAYS: ${e.message}")
        }

        println("DockerApiVersionFix: Testcontainers patched successfully")
    }

    private fun setFieldValue(target: Any, fieldName: String, value: Any?) {
        try {
            val field = target.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(target, value)
        } catch (e: NoSuchFieldException) {
            println("Warning: field '$fieldName' not found in ${target.javaClass.name}")
        }
    }
}
