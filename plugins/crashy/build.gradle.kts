plugins {
    id("mibsdk.plugin")
}

dependencies {
    // This test drives the runtime and the fake car directly, beyond what the plugin-test harness exposes.
    testImplementation(project(":runtime"))
    testImplementation(project(":adapter-fake"))
}
