import mibsdk.build.NewPluginTask
import org.gradle.api.GradleException

// Root project: shared tasks (carJcl) are registered here by the mibsdk.car-library plugin.
plugins {
    // On the root classpath so every subproject shares one plugin classloader (a Kotlin plugin with its own
    // version in a subproject would load build-logic's tasks a second time).
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    // Not applied to the root project: only its classes, so NewPluginTask can be registered below.
    id("mibsdk.car-library") apply false
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
val sdkModules = listOf("compat", "api", "platform", "runtime", "adapter-fake", "plugin-test")
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

// The template is the starting point of every outside plugin, so the build can scaffold one:
//   ./gradlew newPlugin -Pid=my-plugin -Pdir=../my-plugin [-Ppackage=com.example.myplugin]
// It writes templates/plugin there with the id, package and class renamed, and points the new project's
// gradle.properties at this checkout's build/repo (so run `./gradlew publishLocal` at least once).
tasks.register<NewPluginTask>("newPlugin") {
    templateDir.set(layout.projectDirectory.dir("templates/plugin"))
    pluginId.set(providers.gradleProperty("id"))
    packageName.set(providers.gradleProperty("package"))
    destinationDir.set(providers.gradleProperty("dir").map { layout.projectDirectory.dir(it) })
    repoDir.set(localRepoDir)
}

// Proves the plugin template builds outside this repository: scaffolds a project from templates/plugin into
// build/standalone-check/ and builds it there with its own wrapper, resolving the mibsdk plugin and artifacts only
// from build/repo (no :api, no stock jars).
val checkDir = layout.buildDirectory.dir("standalone-check")
val cleanStandaloneCheck = tasks.register<Delete>("cleanStandaloneCheck") {
    group = "verification"
    description = "Removes build/standalone-check/ so standalonePluginCheck can scaffold it again."
    delete(checkDir)
}
val scaffoldForCheck = tasks.register<NewPluginTask>("scaffoldStandaloneCheck") {
    description = "Scaffolds templates/plugin into build/standalone-check/ with -Pid=check-plugin."
    mustRunAfter(cleanStandaloneCheck)
    templateDir.set(layout.projectDirectory.dir("templates/plugin"))
    pluginId.set("check-plugin")
    destinationDir.set(checkDir)
    repoDir.set(localRepoDir)
}

val standalonePluginCheck = tasks.register<Exec>("standalonePluginCheck") {
    group = "verification"
    description = "Scaffolds templates/plugin with newPlugin and builds it outside the project graph against build/repo."
    dependsOn("publishLocal", cleanStandaloneCheck, scaffoldForCheck)

    val template = layout.projectDirectory.dir("templates/plugin")
    val repoDir = localRepoDir
    val sdkVersion = providers.gradleProperty("mibsdk.version")
    val windows = System.getProperty("os.name").lowercase().contains("windows")
    val jdk8Home = providers.gradleProperty("mibsdk.jdk8Home")

    inputs.files(fileTree(template) { exclude("build/**", ".gradle/**", ".kotlin/**") })
        .withPropertyName("template").withPathSensitivity(PathSensitivity.RELATIVE)
    // maven-metadata.xml carries a publication timestamp: only the artifacts themselves count.
    inputs.files(repoDir.map { fileTree(it) { include("**/*.jar", "**/*.pom", "**/*.module") } })
        .withPropertyName("repo").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("sdkVersion", sdkVersion)
    inputs.property("jdk8Home", jdk8Home.orElse(""))
    outputs.file(checkDir.map { it.file("build/mibsdk/check-plugin.jar") })

    // The scaffolded project's own wrapper, so the template's gradlew is what gets tested.
    workingDir = checkDir.get().asFile
    executable = checkDir.get().file(if (windows) "gradlew.bat" else "gradlew").asFile.absolutePath
    argumentProviders.add(CommandLineArgumentProvider {
        listOfNotNull("-Pmibsdk.repo=" + repoDir.get().asFile.toURI(),
            jdk8Home.orNull?.let { "-Pmibsdk.jdk8Home=$it" }, "mibPluginJar", "test", "--stacktrace")
    })

    doFirst {
        // The scaffolded build file must ask for the SDK version this build publishes, or the template rots silently.
        val buildFile = checkDir.get().file("build.gradle.kts").asFile
        check(buildFile.isFile) { "standalonePluginCheck: the scaffold wrote no $buildFile" }
        val declared = Regex("""id\("mibsdk\.plugin"\)\s+version\s+"([^"]+)"""")
            .find(buildFile.readText())?.groupValues?.get(1)
        check(declared == sdkVersion.get()) {
            "$buildFile asks for mibsdk.plugin version $declared, but mibsdk.version is ${sdkVersion.get()}"
        }
    }
    doLast {
        val jar = checkDir.get().file("build/mibsdk/check-plugin.jar").asFile
        check(jar.isFile) { "standalonePluginCheck: $jar was not built" }
        // The scaffolded project's own host test must have run (the convention wires the harness), not just compiled.
        val results = checkDir.get().dir("build/test-results/test").asFile.listFiles { f -> f.extension == "xml" }.orEmpty()
        check(results.isNotEmpty()) { "standalonePluginCheck: the scaffolded project's tests left no test-results XML" }
        // Nothing may still name the template's placeholders.
        val leftovers = checkDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kts", "java", "json", "properties") }
            .filter { f -> f.readText().contains("my-plugin") || f.readText().contains("example.myplugin") }
            .toList()
        check(leftovers.isEmpty()) { "standalonePluginCheck: the scaffold left the template's names in $leftovers" }
    }
}

tasks.named("check") {
    dependsOn(standalonePluginCheck)
}

tasks.register<Exec>("pullStockJars") {
    group = "help"
    description = "Copies lsd.jxe and DSITracer.jar from a unit over SSH and converts lsd.jxe with jxe2jar."
    val unit = providers.gradleProperty("unit").orElse(providers.environmentVariable("MIBSDK_HOST"))
    val stockDir = providers.gradleProperty("stockDir").orElse("build/stock")
    val jxe2jarSrc = providers.gradleProperty("jxe2jarSrc").orElse(providers.environmentVariable("JXE2JAR_SRC"))
    workingDir = projectDir
    environment("MIBSDK_STOCK_DIR", stockDir.get())
    environment("JXE2JAR_SRC", jxe2jarSrc.orElse("").get())
    environment("MIBSDK_WITH_JARS", providers.gradleProperty("withJars").orElse("0").get())
    commandLine("bash", "tools/pull-stock-jars.sh", unit.orElse("").get())
}

// ---- Documentation ---------------------------------------------------------------------------------

val docsSiteDir = layout.buildDirectory.dir("docs-site")

val docsApi = tasks.register("docsApi") {
    group = "documentation"
    description = "Generates the Dokka API reference in build/docs-api."
    dependsOn(":docs-api:dokkaGenerate")
}

val docsCheck = tasks.register<Exec>("docsCheck") {
    group = "documentation"
    description = "Checks the documentation against the samples, the SDK version and the API level."
    workingDir = projectDir
    commandLine("python3", "tools/check_docs.py")
}

val docsSite = tasks.register<Exec>("docsSite") {
    group = "documentation"
    description = "Builds the MkDocs site in build/docs-site, with the generated API reference and the local Maven repo."
    dependsOn(docsApi, docsCheck)
    workingDir = projectDir
    commandLine("mkdocs", "build", "--strict", "--site-dir", docsSiteDir.get().asFile.absolutePath)

    doFirst {
        val mkdocs = try {
            ProcessBuilder("mkdocs", "--version").redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw GradleException("mkdocs is not installed: run `python3 -m pip install -r docs/requirements.txt`", e)
        }
        if (mkdocs.waitFor() != 0) {
            throw GradleException("mkdocs is not usable: run `python3 -m pip install -r docs/requirements.txt`")
        }
    }

    doLast {
        val site = docsSiteDir.get().asFile
        copy {
            from(layout.buildDirectory.dir("docs-api"))
            into(File(site, "api"))
        }
        if (localRepoDir.get().asFile.isDirectory) {
            copy {
                from(localRepoDir)
                into(File(site, "maven"))
            }
        }
        File(site, ".nojekyll").writeText("")
    }
}
