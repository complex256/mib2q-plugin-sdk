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

gradlePlugin {
    plugins {
        register("carLibrary") {
            id = "mibsdk.car-library"
            implementationClass = "mibsdk.build.CarLibraryPlugin"
        }
    }
}
