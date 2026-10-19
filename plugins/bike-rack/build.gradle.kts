plugins {
    id("org.jetbrains.kotlin.jvm")
    id("mibsdk.plugin")
}

dependencies {
    testImplementation(project(":runtime"))
    testImplementation(project(":adapter-fake"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

// The test loads the real built plugin jar, as the device does.
val pluginJar = tasks.named<Jar>("mibPluginJar").flatMap { it.archiveFile }

tasks.test {
    useJUnitPlatform()
    inputs.file(pluginJar).withPropertyName("pluginJar").withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dmibsdk.pluginJar=" + pluginJar.get().asFile.absolutePath)
    })
}

// Tests run on the host JVM (the main source set is pinned to 1.8 by mibsdk.plugin).
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
}
