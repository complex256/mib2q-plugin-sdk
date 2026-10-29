// Where Gradle finds the mibsdk build plugin and the SDK artifacts.
//
// By default this is the published repository. To build against a local checkout instead, set mibsdk.repo in
// gradle.properties (or pass -Pmibsdk.repo=...):
//
//   mibsdk.repo=file:///absolute/path/to/mib2q-plugin-sdk/build/repo
//
// after running `./gradlew publishLocal` in that checkout.
pluginManagement {
    repositories {
        val sdkRepo = providers.gradleProperty("mibsdk.repo").orNull?.takeIf { it.isNotBlank() }
            ?: "https://complex256.github.io/mib2q-plugin-sdk/maven"
        maven { url = uri(sdkRepo) }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        val sdkRepo = providers.gradleProperty("mibsdk.repo").orNull?.takeIf { it.isNotBlank() }
            ?: "https://complex256.github.io/mib2q-plugin-sdk/maven"
        maven { url = uri(sdkRepo) }
        mavenCentral()
    }
}

plugins {
    // Downloads a JDK 8 when none is installed (the build needs one to lower the classes).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "my-plugin"
