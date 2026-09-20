plugins {
    `java-library`
    alias(libs.plugins.jmh)
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

val verifyRuntimeIsolation = tasks.register("verifyRuntimeIsolation") {
    group = "verification"
    description = "Fails when evaluation-core gains a production runtime dependency."
    doLast {
        val artifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        check(artifacts.isEmpty()) {
            "evaluation-core must remain dependency-free at runtime, found: " +
                artifacts.joinToString { "${it.moduleVersion.id.group}:${it.name}:${it.moduleVersion.id.version}" }
        }
    }
}

tasks.check {
    dependsOn(verifyRuntimeIsolation)
}
