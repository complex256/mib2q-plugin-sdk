plugins {
    `java-gradle-plugin`
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 11
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.test {
    useJUnitPlatform()
    providers.gradleProperty("mibsdk.stockLsdJar").orNull?.let { systemProperty("mibsdk.stockLsdJar", it) }
    providers.gradleProperty("mibsdk.jdk8Home").orNull?.let { systemProperty("mibsdk.jdk8Home", it) }
    // MibPluginPluginFunctionalTest builds the repo's compat, api and plugins/hello through TestKit.
    systemProperty("mibsdk.repoRoot", layout.projectDirectory.dir("..").asFile.absolutePath)
    inputs.files(fileTree("../compat/src"), fileTree("../api/src"), fileTree("../plugins/hello/src"))
        .withPropertyName("sdkSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files("../compat/build.gradle.kts", "../api/build.gradle.kts", "../plugins/hello/build.gradle.kts")
        .withPropertyName("sdkBuildScripts").withPathSensitivity(PathSensitivity.RELATIVE)
}

gradlePlugin {
    plugins {
        register("carLibrary") {
            id = "mibsdk.car-library"
            implementationClass = "mibsdk.build.CarLibraryPlugin"
        }
        register("mibPlugin") {
            id = "mibsdk.plugin"
            implementationClass = "mibsdk.build.MibPluginPlugin"
        }
    }
}
