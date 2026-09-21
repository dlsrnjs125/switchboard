plugins {
    application
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

application {
    mainClass = "io.github.dlsrnjs125.switchboard.controlplane.ControlPlaneApplication"
}

dependencies {
    implementation(project(":libs:observability"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.kafka:spring-kafka")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.2")
    implementation("com.networknt:json-schema-validator:1.5.8")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-toxiproxy")
    testImplementation("org.testcontainers:testcontainers-kafka")
}

tasks.processResources {
    from(rootProject.file("contracts/snapshot-schema/configuration-snapshot-v1.schema.json")) {
        into("contracts/snapshot-schema")
    }
}

tasks.test {
    systemProperty("switchboard.repositoryRoot", rootProject.projectDir.absolutePath)
}
