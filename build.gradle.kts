plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.4.4" apply false
	id("io.spring.dependency-management") version "1.1.7"
	`java-library`
	`maven-publish`
}

group = "tn.cyberious"
version = "0.3.0"
description = "Spring Boot Starter for Zitadel IAM Management API"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
	withSourcesJar()
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}

dependencyManagement {
	imports {
		mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
	}
}

dependencies {
	api("org.springframework.boot:spring-boot-starter-web")
	api("com.fasterxml.jackson.module:jackson-module-kotlin")
	api("org.jetbrains.kotlin:kotlin-reflect")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	implementation("com.nimbusds:nimbus-jose-jwt:10.0.2")
	implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.1"))
	testImplementation("org.testcontainers:testcontainers")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")
	// Force newer docker-java for Docker 29.x compatibility (API >= 1.44)
	testImplementation("com.github.docker-java:docker-java-api:3.5.1")
	testImplementation("com.github.docker-java:docker-java-core:3.5.1")
	testImplementation("com.github.docker-java:docker-java-transport-zerodep:3.5.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.test {
	useJUnitPlatform {
		excludeTags("integration")
	}
}

tasks.register<Test>("integrationTest") {
	description = "Run integration tests with Testcontainers"
	group = "verification"
	useJUnitPlatform {
		includeTags("integration")
	}
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	shouldRunAfter(tasks.test)
	val dockerHost = System.getenv("DOCKER_HOST") ?: "tcp://localhost:2375"
	val dockerApiVersion = System.getenv("DOCKER_API_VERSION") ?: "1.44"
	environment("DOCKER_HOST", dockerHost)
	environment("DOCKER_API_VERSION", dockerApiVersion)
	systemProperty("docker.host", dockerHost)
	systemProperty("docker.api.version", dockerApiVersion)
}

publishing {
	publications {
		create<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}
}
