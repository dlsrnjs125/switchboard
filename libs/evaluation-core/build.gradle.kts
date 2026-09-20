plugins {
    `java-library`
    alias(libs.plugins.jmh)
}

dependencies {
    testImplementation(libs.jackson.databind)
}

jmh {
    jmhVersion = "1.37"
    warmupIterations = 3
    iterations = 5
    fork = 1
    timeOnIteration = "1s"
    warmup = "1s"
    benchmarkMode = listOf("avgt")
    timeUnit = "ns"
    resultFormat = "JSON"
    resultsFile = project.file("${project.layout.buildDirectory.get()}/reports/jmh/results.json")
}

tasks.test {
    systemProperty("switchboard.repositoryRoot", rootProject.projectDir.absolutePath)
}

val productionRuntimeClasspath = configurations.runtimeClasspath

val verifyRuntimeIsolation = tasks.register("verifyRuntimeIsolation") {
    group = "verification"
    description = "Fails when evaluation-core gains a production runtime dependency."
    inputs.files(productionRuntimeClasspath)
        .withPropertyName("runtimeClasspath")
        .withNormalizer(ClasspathNormalizer::class.java)
    doLast {
        val dependencies = inputs.files.files
        check(dependencies.isEmpty()) {
            "evaluation-core must remain dependency-free at runtime, found: " +
                dependencies.joinToString { it.name }
        }
    }
}

tasks.check {
    dependsOn(verifyRuntimeIsolation)
}
