plugins {
    `java-library`
    id("org.jetbrains.dokka") version "2.2.0"
}

// Documentation-only module: it compiles nothing and ships nowhere. It gives Dokka one source set over
// the public API, the test harness and the fake car, so the published reference is a single site.
repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":api"))
    implementation(project(":platform"))
    implementation(project(":runtime"))
    implementation(project(":adapter-fake"))
    implementation(project(":plugin-test"))
}

dokka {
    dokkaPublications.html {
        outputDirectory.set(rootProject.layout.buildDirectory.dir("docs-api"))
        moduleName.set("mibsdk")
    }
    dokkaSourceSets.configureEach {
        sourceRoots.from(
            project(":api").file("src/main/java"),
            project(":plugin-test").file("src/main/java"),
            project(":adapter-fake").file("src/main/java"),
        )
        sourceLink {
            localDirectory.set(project(":api").file("src/main/java"))
            remoteUrl.set(uri("https://github.com/complex256/mib2q-plugin-sdk/tree/main/api/src/main/java"))
            remoteLineSuffix.set("#L")
        }
    }
}
