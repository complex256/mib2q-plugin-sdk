// Root project: shared tasks (carJcl) are registered here by the mibsdk.car-library plugin.
plugins {
    // On the root classpath so every subproject shares one plugin classloader (a Kotlin plugin with its own
    // version in a subproject would load build-logic's tasks a second time).
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
}
