plugins {
    application
}

dependencies {
    implementation(project(":sdk:java-openfeature-provider"))
}

application {
    mainClass = "io.github.dlsrnjs125.switchboard.demo.SampleServiceApplication"
}
