// Where Gradle finds the mibsdk build plugin and the SDK artifacts. mibsdk.repo (gradle.properties) is the URL of a
// Maven repository holding them: until they are hosted, run `./gradlew publishLocal` in an SDK checkout and point
// mibsdk.repo at that checkout's build/repo directory (as a file: URL).
pluginManagement {
    repositories {
        val sdkRepo = providers.gradleProperty("mibsdk.repo").orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException("mibsdk.repo is not set: put the URL of the mibsdk Maven repository in gradle.properties")
        maven { url = uri(sdkRepo) }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        val sdkRepo = providers.gradleProperty("mibsdk.repo").orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException("mibsdk.repo is not set: put the URL of the mibsdk Maven repository in gradle.properties")
        maven { url = uri(sdkRepo) }
        mavenCentral()
    }
}

plugins {
    // Downloads a JDK 8 when none is installed (the build needs one to lower the classes).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "my-plugin"
