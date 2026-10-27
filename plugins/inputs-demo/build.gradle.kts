plugins {
    id("mibsdk.plugin")
}

dependencies {
    testImplementation(project(":plugin-test"))
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
