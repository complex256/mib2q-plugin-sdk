// Root project: shared tasks (carJcl) are registered here by the mibsdk.car-library plugin.
plugins {
    // On the root classpath so every subproject shares one plugin classloader (a Kotlin plugin with its own
    // version in a subproject would load build-logic's tasks a second time).
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
}

// build-logic is an included build (pluginManagement), which `./gradlew check` does not reach by task-name selection:
// the root's own check runs its tests, so the gate covers PngCheck, the shared PNG vectors and the plugin build.
tasks.register("check") {
    group = "verification"
    description = "Runs build-logic's tests (the included build); every subproject's check runs alongside."
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
