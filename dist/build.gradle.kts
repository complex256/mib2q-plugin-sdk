import mibsdk.build.LinkCheckTask
import mibsdk.build.Toolchain

plugins {
    base
    // Only to put build-logic's task types on this script's classpath; dist compiles nothing.
    id("mibsdk.car-library") apply false
}

// What goes on the unit: three jars. adapter-fake is host-only and must never appear here.
val runtimeModules = listOf("compat", "api", "platform", "runtime")
val adapterModule = "adapter-hqc"
(runtimeModules + adapterModule).forEach { evaluationDependsOn(":$it") }

fun carJar(module: String) = project(":$module").tasks.named<Jar>("carJar")

val stockLsdJar: File = Toolchain.stockLsdJar(project)
val stockDsiTracerJar: File = Toolchain.stockDsiTracerJar(project)
val retrotranslatorRuntime = project(":runtime").configurations.getByName("retrotranslatorRuntime")

val distDir = layout.buildDirectory.dir("dist")

val runtimeJar = tasks.register<Jar>("runtimeJar") {
    description = "mibsdk-runtime.jar: compat + api + platform + runtime, all class-file v48."
    archiveFileName.set("mibsdk-runtime.jar")
    destinationDirectory.set(distDir)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    for (m in runtimeModules) {
        from(carJar(m).map { zipTree(it.archiveFile) }) { exclude("META-INF/MANIFEST.MF") }
    }
}

val adapterJar = tasks.register<Jar>("adapterJar") {
    description = "mibsdk-adapter-hqc.jar: the configurator shell and bundle activator (v48)."
    archiveFileName.set("mibsdk-adapter-hqc.jar")
    destinationDirectory.set(distDir)
    from(carJar(adapterModule).map { zipTree(it.archiveFile) }) { exclude("META-INF/MANIFEST.MF") }
}

val retrotranslatorJar = tasks.register<Copy>("retrotranslatorJar") {
    description = "mibsdk-rt-retrotranslator.jar: the non-embedded Retrotranslator runtime, shipped once."
    from(retrotranslatorRuntime)
    into(distDir)
    rename { "mibsdk-rt-retrotranslator.jar" }
}

val distLinkCheck = tasks.register<LinkCheckTask>("distLinkCheck") {
    description = "Combined LinkCheck: the three unit jars against the car JCL, the stock jars and each other."
    group = "verification"
    // The Retrotranslator runtime is a library, not checked wholesale (most of it is never reached on the car);
    // its method bodies are followed from every member the two SDK jars actually reference, as in the module checks.
    checked.from(runtimeJar.flatMap { it.archiveFile }, adapterJar.flatMap { it.archiveFile })
    transitiveLibs.from(retrotranslatorJar.map { distDir.get().file("mibsdk-rt-retrotranslator.jar") })
    classpath.from(rootProject.tasks.named("carJcl").map { it.outputs.files })
    classpath.from(runtimeJar.flatMap { it.archiveFile }, adapterJar.flatMap { it.archiveFile },
        retrotranslatorJar.map { distDir.get().file("mibsdk-rt-retrotranslator.jar") })
    classpath.from(stockLsdJar, stockDsiTracerJar)
    report.set(layout.buildDirectory.file("reports/mibsdk/dist-linkcheck.txt"))
}

tasks.register("assembleUnit") {
    group = "build"
    description = "Builds build/dist/{mibsdk-runtime,mibsdk-adapter-hqc,mibsdk-rt-retrotranslator}.jar and link-checks them together."
    dependsOn(runtimeJar, adapterJar, retrotranslatorJar, distLinkCheck)
}

tasks.named("assemble") { dependsOn("assembleUnit") }
tasks.named("check") { dependsOn(distLinkCheck) }
