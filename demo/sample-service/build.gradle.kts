plugins {
    application
}

dependencies {
    implementation(project(":sdk:java-openfeature-provider"))
    implementation(project(":libs:observability"))
}

application {
    mainClass = "io.github.dlsrnjs125.switchboard.demo.SampleServiceApplication"
}
