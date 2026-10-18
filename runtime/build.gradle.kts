plugins {
    id("mibsdk.car-library")
}

evaluationDependsOn(":compat")
evaluationDependsOn(":api")
evaluationDependsOn(":platform")

dependencies {
    api(project(":api"))
    api(project(":platform"))
    testImplementation(project(":adapter-fake"))
}

mibsdk {
    // LinkCheck resolves mibsdk.api.*, mibsdk.platform.* and any Retrotranslator-introduced mibsdk.compat.* references here.
    carClasspath.from(project(":api").tasks.named("carJar"))
    carClasspath.from(project(":compat").tasks.named("carJar"))
    carClasspath.from(project(":platform").tasks.named("carJar"))
}

// SdkRuntimeLifecycleTest loads the real sample plugin jars. Plugins depend only on api/compat, so no cycle.
for (id in listOf("hello", "crashy")) {
    evaluationDependsOn(":plugins:$id")
    val jar = project(":plugins:$id").tasks.named<Jar>("mibPluginJar").flatMap { it.archiveFile }
    tasks.test {
        inputs.file(jar).withPropertyName("sampleJar.$id").withPathSensitivity(PathSensitivity.NONE)
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-Dmibsdk.sampleJar.$id=" + jar.get().asFile.absolutePath)
        })
    }
}
