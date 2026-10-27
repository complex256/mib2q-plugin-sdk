plugins {
    id("mibsdk.plugin")
}

dependencies {
    // Host tests only: the real runtime and the fake car. None of this goes into the plugin jar.
    testImplementation(project(":runtime"))
    testImplementation(project(":adapter-fake"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

// The test loads the real built plugin jar, as the unit does.
val pluginJar = tasks.named<Jar>("mibPluginJar").flatMap { it.archiveFile }

tasks.test {
    useJUnitPlatform()
    inputs.file(pluginJar).withPropertyName("pluginJar").withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dmibsdk.pluginJar=" + pluginJar.get().asFile.absolutePath)
    })
}
