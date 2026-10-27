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

evaluationDependsOn(":plugins:hello-menu")

dependencies {
    // The harness exposes FakeUi and FakePlatform; the runtime and api stay behind it.
    api(project(":adapter-fake"))
    implementation(project(":runtime"))
    implementation(project(":api"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

// PluginTestHarnessTest starts the real hello-menu jar, as a plugin's own tests do.
val helloMenuJar = project(":plugins:hello-menu").tasks.named<Jar>("mibPluginJar").flatMap { it.archiveFile }
val helloMenuIcons = project(":plugins:hello-menu").layout.projectDirectory.dir("src/main/icons")

tasks.test {
    useJUnitPlatform()
    inputs.file(helloMenuJar).withPropertyName("helloMenuJar").withPathSensitivity(PathSensitivity.NONE)
    inputs.dir(helloMenuIcons).withPropertyName("helloMenuIcons").withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dmibsdk.pluginJar=" + helloMenuJar.get().asFile.absolutePath,
            "-Dmibsdk.pluginIcons=" + helloMenuIcons.asFile.absolutePath,
        )
    })
}
