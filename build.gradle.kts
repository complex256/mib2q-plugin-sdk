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

// Proves the plugin template builds outside this repository: copies templates/plugin to build/standalone-check/ and builds
// it there with this wrapper, resolving the mibsdk plugin and artifacts only from build/repo (no :api, no stock jars).
val standalonePluginCheck = tasks.register<Exec>("standalonePluginCheck") {
    group = "verification"
    description = "Builds templates/plugin in a copy outside the project graph against build/repo (mibPluginJar and test)."
    dependsOn("publishLocal")

    val template = layout.projectDirectory.dir("templates/plugin")
    val checkDir = layout.buildDirectory.dir("standalone-check")
    val repoDir = localRepoDir
    val sdkVersion = providers.gradleProperty("mibsdk.version")
    val wrapper = layout.projectDirectory.file(if (org.gradle.internal.os.OperatingSystem.current().isWindows) "gradlew.bat" else "gradlew")

    inputs.files(fileTree(template) { exclude("build/**", ".gradle/**", ".kotlin/**") })
        .withPropertyName("template").withPathSensitivity(PathSensitivity.RELATIVE)
    // maven-metadata.xml carries a publication timestamp: only the artifacts themselves count.
    inputs.files(repoDir.map { fileTree(it) { include("**/*.jar", "**/*.pom", "**/*.module") } }).withPropertyName("repo").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("sdkVersion", sdkVersion)
    outputs.file(checkDir.map { it.file("build/mibsdk/my-plugin.jar") })

    executable = wrapper.asFile.absolutePath
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("-p", checkDir.get().asFile.absolutePath, "-Pmibsdk.repo=" + repoDir.get().asFile.toURI(),
            "mibPluginJar", "test", "--stacktrace")
    })

    doFirst {
        // The template must ask for the SDK version this build publishes, or it rots silently.
        val declared = Regex("""id\("mibsdk\.plugin"\)\s+version\s+"([^"]+)"""")
            .find(template.file("build.gradle.kts").asFile.readText())?.groupValues?.get(1)
        check(declared == sdkVersion.get()) {
            "templates/plugin/build.gradle.kts asks for mibsdk.plugin version $declared, but mibsdk.version is ${sdkVersion.get()}"
        }
        val dir = checkDir.get().asFile
        dir.deleteRecursively()
        template.asFile.copyRecursively(dir)
    }
    doLast {
        val jar = checkDir.get().file("build/mibsdk/my-plugin.jar").asFile
        check(jar.isFile) { "standalonePluginCheck: $jar was not built" }
    }
}

tasks.named("check") {
    dependsOn(standalonePluginCheck)
}
