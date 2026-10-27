import java.util.Properties

plugins {
    `java-gradle-plugin`
    `maven-publish`
}

// An included build does not inherit the root project's properties: the SDK version is read from the root file.
val sdkVersion: String = providers.fileContents(layout.projectDirectory.file("../gradle.properties")).asText.map { text ->
    Properties().apply { load(text.reader()) }.getProperty("mibsdk.version")
        ?: error("mibsdk.version is missing from ../gradle.properties")
}.get()

group = "dev.complex256.mibsdk"
version = sdkVersion

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

// SdkCoordinates reads these two resources: the version and the permission catalog are stamped in at build time.
val sdkProperties = tasks.register<WriteProperties>("sdkProperties") {
    destinationFile = layout.buildDirectory.file("generated/sdk/sdk.properties")
    property("group", "dev.complex256.mibsdk")
    property("version", sdkVersion)
}
tasks.processResources {
    from(sdkProperties) { into("mibsdk/build") }
    from("../api/permissions.json") { into("mibsdk/build") }
    inputs.file("../api/permissions.json").withPropertyName("permissionCatalogSource")
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.property("sdkVersion", sdkVersion)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") artifactId = "mibsdk-gradle-plugin"
    }
    repositories {
        maven {
            name = "local"
            url = uri(layout.projectDirectory.dir("../build/repo"))
        }
    }
}

tasks.test {
    useJUnitPlatform()
    providers.gradleProperty("mibsdk.stockLsdJar").orNull?.let { systemProperty("mibsdk.stockLsdJar", it) }
    providers.gradleProperty("mibsdk.jdk8Home").orNull?.let { systemProperty("mibsdk.jdk8Home", it) }
    systemProperty("mibsdk.mibpkg", findProperty("mibsdk.mibpkg") ?: "")
    // MibPluginPluginFunctionalTest builds the repo's compat, api and plugins/hello through TestKit.
    systemProperty("mibsdk.repoRoot", layout.projectDirectory.dir("..").asFile.absolutePath)
    inputs.files(fileTree("../api/permissions.json"))
        .withPropertyName("permissionCatalog").withPathSensitivity(PathSensitivity.RELATIVE)
    // PngCheckTest.everyBuiltinIconPasses reads the committed built-in set.
    inputs.files(fileTree("../dist/icons")).withPropertyName("builtinIcons").withPathSensitivity(PathSensitivity.RELATIVE)
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
