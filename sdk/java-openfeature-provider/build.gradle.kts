plugins {
    `java-library`
}

dependencies {
    api(project(":libs:evaluation-core"))
    api("dev.openfeature:sdk:1.20.2")
    implementation(project(":contracts"))
    implementation(platform("io.grpc:grpc-bom:1.83.1"))
    implementation("io.grpc:grpc-netty-shaded")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.2")
    implementation("com.networknt:json-schema-validator:1.5.8")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")

    testImplementation("io.grpc:grpc-testing")
}

tasks.processResources {
    from(rootProject.file("contracts/snapshot-schema/configuration-snapshot-v1.schema.json")) {
        into("contracts/snapshot-schema")
    }
}
