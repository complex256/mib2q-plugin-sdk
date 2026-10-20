import mibsdk.build.CheckStampTask
import mibsdk.build.ClassVersionCheckTask
import mibsdk.build.LinkCheckTask
import mibsdk.build.MibpkgBuildTask
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

val distClassVersionCheck = tasks.register<ClassVersionCheckTask>("distClassVersionCheck") {
    description = "Every class in the three unit jars is class-file v48 (the Retrotranslator runtime included)."
    group = "verification"
    classes.from(runtimeJar.map { zipTree(it.archiveFile) }, adapterJar.map { zipTree(it.archiveFile) },
        retrotranslatorJar.map { zipTree(distDir.get().file("mibsdk-rt-retrotranslator.jar")) })
}

// Hashes (not signs) the three jars once distLinkCheck has passed; format in mibsdk.build.CheckStamp.
val unitStamp = tasks.register<CheckStampTask>("unitStamp") {
    description = "build/dist/mibsdk-unit.stamp: SHA-256 of the three unit jars, written after the dist checks."
    group = "verification"
    dependsOn(distLinkCheck, distClassVersionCheck)
    doFirst {
        stamp.get().asFile.delete()
        mibsdk.build.CheckStamp.requireRan(this, listOf(distClassVersionCheck.get(), distLinkCheck.get()))
    }
    jars.from(runtimeJar.flatMap { it.archiveFile }, adapterJar.flatMap { it.archiveFile },
        retrotranslatorJar.map { distDir.get().file("mibsdk-rt-retrotranslator.jar") })
    kind.set("unit")
    checks.set(listOf("classVersion48", "distLinkCheck"))
    stamp.set(distDir.map { it.file("mibsdk-unit.stamp") })
}

tasks.register("assembleUnit") {
    group = "build"
    description = "Builds build/dist/{mibsdk-runtime,mibsdk-adapter-hqc,mibsdk-rt-retrotranslator}.jar and link-checks them together."
    dependsOn(runtimeJar, adapterJar, retrotranslatorJar, distLinkCheck, unitStamp)
}

// The stamp must describe the jars on disk. Any task that rewrites a unit jar (assembleUnit, but also check via
// distLinkCheck, or a jar task run alone) first deletes the stamp and is finalized by unitStamp, which writes it again
// only if the dist checks pass: build/dist holds a matching stamp or none, never a stale one.
listOf(runtimeJar, adapterJar, retrotranslatorJar).forEach {
    it.configure {
        doFirst { distDir.get().file("mibsdk-unit.stamp").asFile.delete() }
        finalizedBy(unitStamp)
    }
}

tasks.named("assemble") { dependsOn("assembleUnit") }
tasks.named("check") { dependsOn(distLinkCheck, unitStamp) }

// The SDK package: the three stamped unit jars, the permission catalog and the [sdk] manifest.
val sdkVersion = providers.gradleProperty("mibsdk.version").get()
val sdkStageDir = layout.buildDirectory.dir("mibpkg-stage")

val mibpkgStage = tasks.register<Sync>("mibpkgStage") {
    description = "Stages manifest.toml, the three unit jars, mibsdk-unit.stamp and permissions.json for mibpkg."
    dependsOn(unitStamp)
    from(runtimeJar.flatMap { it.archiveFile }, adapterJar.flatMap { it.archiveFile },
        retrotranslatorJar.map { distDir.get().file("mibsdk-rt-retrotranslator.jar") })
    from(distDir.map { it.file("mibsdk-unit.stamp") })
    from(rootProject.layout.projectDirectory.file("api/permissions.json"))
    into(sdkStageDir)
    inputs.property("sdkVersion", sdkVersion)
    doFirst {
        if (!distDir.get().file("mibsdk-unit.stamp").asFile.isFile) {
            throw GradleException("dist: mibsdk-unit.stamp is missing: a dist check did not run, so the SDK is not packaged")
        }
    }
    doLast {
        sdkStageDir.get().file("manifest.toml").asFile.writeText(mibsdk.build.MpkStage.sdkManifest(sdkVersion))
    }
}

tasks.register<MibpkgBuildTask>("mibpkgPackage") {
    group = "build"
    description = "build/dist/mibsdk-<version>.mpk with the host mibpkg (-Pmibsdk.mibpkg=<path>); older mibsdk-*.mpk are deleted once the binary is found."
    dependsOn(mibpkgStage, unitStamp)
    stage.set(sdkStageDir)
    mibpkg.set(Toolchain.mibpkg(project))
    binary.set(Toolchain.mibpkgBinary(project))
    stalePrefix.set("mibsdk-")
    mpk.set(distDir.map { it.file("mibsdk-$sdkVersion.mpk") })
}
