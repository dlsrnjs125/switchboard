plugins {
    id("com.google.protobuf") version "0.10.0"
}

dependencies {
    api(platform("io.grpc:grpc-bom:1.83.1"))
    implementation("com.google.protobuf:protobuf-java:4.33.4")
    api("io.grpc:grpc-protobuf")
    api("io.grpc:grpc-stub")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.20.2")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.2")
    testImplementation("com.networknt:json-schema-validator:1.5.8")
    testImplementation("io.swagger.parser.v3:swagger-parser:2.1.37")
    testImplementation("io.github.erdtman:java-json-canonicalization:1.1")
}

sourceSets { main { proto.srcDir("proto") } }

protobuf { protoc { artifact = "com.google.protobuf:protoc:4.33.4" } }

protobuf {
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.83.1"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                create("grpc")
            }
        }
    }
}

tasks.test { systemProperty("contract.root", projectDir.absolutePath) }
