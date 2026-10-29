pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Provisions a JDK 8 (Retrolambda, Retrotranslator, the Java 1.4 compile) when none is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "mibsdk"

include("compat")
include("api")
include("platform")
include("runtime")
include("adapter-fake")
include("plugin-test")
include("adapter-hqc")
include("dist")
include("plugins:hello", "plugins:crashy", "plugins:coffee-timer")
include("spikes:hmi-ui")
