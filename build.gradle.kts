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

// Publishing: the SDK artifacts and the mibsdk.* Gradle plugins go to the file repo build/repo (repository "local") and
// nowhere else. Plugins outside this repository resolve them by coordinate (dev.complex256.mibsdk:<module>:<version>).
val sdkModules = listOf("compat", "api", "platform", "runtime", "adapter-fake")
val carClassifierModules = setOf("compat", "api")
val localRepoDir = layout.buildDirectory.dir("repo")

configure(sdkModules.map { project(it) }) {
    group = "dev.complex256.mibsdk"
    version = providers.gradleProperty("mibsdk.version").get()

    val moduleName = name
    pluginManager.withPlugin("java-library") {
        apply(plugin = "maven-publish")
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            if (moduleName == "api") withJavadocJar()
        }
        if (moduleName == "api") {
            // Javadoc warnings (missing comments, unresolved tags) are not publication failures.
            tasks.withType<Javadoc>().configureEach {
                isFailOnError = false
                (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
            }
        }
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") {
                from(components["java"])
            }
            repositories.maven {
                name = "local"
                url = uri(localRepoDir.get().asFile)
            }
        }
    }
    if (name in carClassifierModules) {
        // carJar is registered by mibsdk.car-library after java-library is applied, so wire it once the plugin is in.
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications.named<MavenPublication>("maven") {
                    artifact(tasks.named("carJar")) { classifier = "car" }
                }
            }
        }
    }
}

tasks.register("publishLocal") {
    group = "publishing"
    description = "Publishes the SDK artifacts and the mibsdk.* Gradle plugins to build/repo (the only target)."
    sdkModules.forEach { dependsOn(":$it:publishAllPublicationsToLocalRepository") }
    dependsOn(gradle.includedBuild("build-logic").task(":publishAllPublicationsToLocalRepository"))
}
