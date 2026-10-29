plugins {
    `java-library`
}

// A host module, like adapter-fake: plugin tests run it on the developer's JDK. Java 8 language level.
java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
}

repositories {
    mavenCentral()
}

evaluationDependsOn(":plugins:coffee-timer")

dependencies {
    // The harness exposes FakeUi and FakePlatform; the runtime and api stay behind it.
    api(project(":adapter-fake"))
    implementation(project(":runtime"))
    implementation(project(":api"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

// PluginTestHarnessTest starts the real coffee-timer jar, as a plugin's own tests do.
val coffeeJar = project(":plugins:coffee-timer").tasks.named<Jar>("mibPluginJar").flatMap { it.archiveFile }
val coffeeIcons = project(":plugins:coffee-timer").layout.projectDirectory.dir("src/main/icons")

tasks.test {
    useJUnitPlatform()
    inputs.file(coffeeJar).withPropertyName("coffeeJar").withPathSensitivity(PathSensitivity.NONE)
    inputs.dir(coffeeIcons).withPropertyName("coffeeIcons").withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dmibsdk.pluginJar=" + coffeeJar.get().asFile.absolutePath,
            "-Dmibsdk.pluginIcons=" + coffeeIcons.asFile.absolutePath,
        )
    })
}
