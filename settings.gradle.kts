pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "switchboard"

include(
    "contracts",
    "services:control-plane",
    "services:distribution",
    "libs:evaluation-core",
    "sdk:java-openfeature-provider",
    "demo:sample-service",
)
