import mibsdk.build.Toolchain

// Throwaway bench spike for Plan 2 (UI), Task 1: Car drawer rows, labels, presses and the popup-65 status line.
// Not part of the distribution; installed by run.sh only.
plugins {
    id("mibsdk.car-library")
}

evaluationDependsOn(":adapter-hqc")
evaluationDependsOn(":runtime")
evaluationDependsOn(":platform")
evaluationDependsOn(":api")

val stockLsdJar: File = Toolchain.stockLsdJar(project)
val stockDsiTracerJar: File = Toolchain.stockDsiTracerJar(project)

dependencies {
    compileOnly(files(stockLsdJar, stockDsiTracerJar))
    compileOnly(project(":adapter-hqc"))
    compileOnly(project(":runtime"))
}

mibsdk {
    carClasspath.from(stockLsdJar, stockDsiTracerJar)
    carClasspath.from(project(":compat").tasks.named("carJar"))
    carClasspath.from(project(":runtime").tasks.named("carJar"))
    carClasspath.from(project(":platform").tasks.named("carJar"))
    carClasspath.from(project(":api").tasks.named("carJar"))
    carClasspath.from(project(":adapter-hqc").tasks.named("carJar"))
}

tasks.named<Jar>("carJar") {
    archiveFileName.set("mibsdk-spike-ui.jar")
    dependsOn("linkCheck")
}
