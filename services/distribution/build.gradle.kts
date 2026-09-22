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
    implementation(project(":libs:observability"))
    implementation(platform("io.grpc:grpc-bom:1.83.1"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("io.grpc:grpc-netty-shaded")
    implementation("io.grpc:grpc-services")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.2")
    implementation("com.networknt:json-schema-validator:1.5.8")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")
    implementation("org.springframework.security:spring-security-crypto")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

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
    useJUnitPlatform {
        excludeTags("phase9")
    }
}

tasks.register<Test>("phase9GrpcEvidence") {
    group = "verification"
    description = "Runs the opt-in Phase 9 gRPC capacity evidence workload."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("switchboard.repositoryRoot", rootProject.projectDir.absolutePath)
    systemProperty(
        "switchboard.phase9.result",
        project.layout.buildDirectory.file("reports/phase-09/grpc-capacity.json").get().asFile.absolutePath,
    )
    useJUnitPlatform {
        includeTags("phase9-grpc")
    }
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("phase9PublishPropagationEvidence") {
    group = "verification"
    description = "Measures Phase 9 publish commit-to-SDK propagation stages."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("switchboard.repositoryRoot", rootProject.projectDir.absolutePath)
    systemProperty(
        "switchboard.phase9.propagation.result",
        project.layout.buildDirectory.file("reports/phase-09/publish-propagation.json").get().asFile.absolutePath,
    )
    useJUnitPlatform {
        includeTags("phase9-propagation")
    }
    shouldRunAfter(tasks.test)
}
