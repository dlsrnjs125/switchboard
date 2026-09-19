plugins {
    id("com.google.protobuf") version "0.10.0"
}

dependencies {
    implementation("com.google.protobuf:protobuf-java:4.33.4")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.20.2")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.2")
    testImplementation("com.networknt:json-schema-validator:1.5.8")
    testImplementation("io.swagger.parser.v3:swagger-parser:2.1.37")
    testImplementation("io.github.erdtman:java-json-canonicalization:1.1")
}

sourceSets { main { proto.srcDir("proto") } }

protobuf { protoc { artifact = "com.google.protobuf:protoc:4.33.4" } }

tasks.test { systemProperty("contract.root", projectDir.absolutePath) }
