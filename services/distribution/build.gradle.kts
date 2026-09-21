plugins {
    application
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

application {
    mainClass = "io.github.dlsrnjs125.switchboard.distribution.DistributionApplication"
}

dependencies {
    implementation(project(":contracts"))
    implementation(platform("io.grpc:grpc-bom:1.83.1"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.grpc:grpc-netty-shaded")
    implementation("io.grpc:grpc-services")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.2")
    implementation("com.networknt:json-schema-validator:1.5.8")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")
    implementation("org.springframework.security:spring-security-crypto")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("io.grpc:grpc-testing")
    testImplementation(project(":services:control-plane"))
    testImplementation(project(":sdk:java-openfeature-provider"))
    testImplementation(libs.jackson.databind)
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.processResources {
    from(rootProject.file("contracts/snapshot-schema/configuration-snapshot-v1.schema.json")) {
        into("contracts/snapshot-schema")
    }
}

tasks.test {
    systemProperty("switchboard.repositoryRoot", rootProject.projectDir.absolutePath)
}
