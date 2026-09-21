plugins {
    `java-library`
    alias(libs.plugins.jmh)
}

dependencies {
    api(project(":libs:evaluation-core"))
    implementation(project(":libs:observability"))
    api("dev.openfeature:sdk:1.20.2")
    implementation(project(":contracts"))
    implementation(platform("io.grpc:grpc-bom:1.83.1"))
    implementation("io.grpc:grpc-netty-shaded")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.2")
    implementation("com.networknt:json-schema-validator:1.5.8")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")

    testImplementation("io.grpc:grpc-testing")
    testImplementation("org.openjdk.jol:jol-core:0.17")
}

tasks.processResources {
    from(rootProject.file("contracts/snapshot-schema/configuration-snapshot-v1.schema.json")) {
        into("contracts/snapshot-schema")
    }
}

jmh {
    jmhVersion = "1.37"
    warmupIterations = 3
    iterations = 5
    fork = 1
    timeOnIteration = "1s"
    warmup = "1s"
    benchmarkMode = listOf("sample")
    timeUnit = "ns"
    resultFormat = "JSON"
    profilers = listOf("gc")
    resultsFile = project.file("${project.layout.buildDirectory.get()}/reports/jmh/phase-09-evaluation.json")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("phase9")
    }
}

tasks.register<Test>("phase9SnapshotFootprintEvidence") {
    group = "verification"
    description = "Records the retained heap footprint of Phase 9 SDK Snapshots."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty(
        "switchboard.phase9.footprint.result",
        project.layout.buildDirectory.file("reports/phase-09/snapshot-footprint.json").get().asFile.absolutePath,
    )
    systemProperty("jol.magicFieldOffset", "true")
    useJUnitPlatform {
        includeTags("phase9")
    }
    shouldRunAfter(tasks.test)
}
