import mibsdk.build.HostStockJarTask
import mibsdk.build.Toolchain

plugins {
    id("mibsdk.car-library")
}

evaluationDependsOn(":compat")
evaluationDependsOn(":api")
evaluationDependsOn(":platform")
evaluationDependsOn(":runtime")

// Stock jars (never committed): LSD's configurator SPI, OSGi and DSI interfaces; DSITracer's TraceConfigurator.
val stockLsdJar: File = Toolchain.stockLsdJar(project)
val stockDsiTracerJar: File = Toolchain.stockDsiTracerJar(project)
val stockPresent = stockLsdJar.isFile && stockDsiTracerJar.isFile
val stockMissingMessage = "adapter-hqc needs the stock jars: lsd.jar at $stockLsdJar " +
    "(-Pmibsdk.stockLsdJar=...) and DSITracer.jar at $stockDsiTracerJar (-Pmibsdk.stockDsiTracerJar=...)"

dependencies {
    implementation(project(":runtime"))
    compileOnly(files(stockLsdJar, stockDsiTracerJar))
}

mibsdk {
    carClasspath.from(stockLsdJar, stockDsiTracerJar)
    carClasspath.from(project(":api").tasks.named("carJar"))
    carClasspath.from(project(":compat").tasks.named("carJar"))
    carClasspath.from(project(":platform").tasks.named("carJar"))
    carClasspath.from(project(":runtime").tasks.named("carJar"))
}

// Raw jxe2jar classes do not load on a desktop JVM (ClassFormatError); tests use a repaired copy of just the
// stock packages they touch. The raw stock jars stay compileOnly, so they never reach the test runtime.
val hostStockJar = tasks.register<HostStockJarTask>("hostStockJar") {
    description = "Host-loadable copy of the stock classes the adapter tests need (test classpath only)."
    stockJars.from(stockLsdJar, stockDsiTracerJar)
    packages.set(listOf(
        "de/dreisoft/lsd/",
        "org/osgi/framework/",
        "org/dsi/ifc/base/",
        "org/dsi/ifc/carparkingsystem/",
        "org/dsi/ifc/global/",
        "org/dsi/ifc/powermanagement/",
        "de/audi/tghu/dsitrace/",
        // UI (Car activator, bank, models, drawer widgets): the UI tests construct them on the host.
        "de/audi/atip/",
        "de/audi/tghu/car/hmi/",
        "de/audi/tghu/hmi/",
        "de/esolutions/hmi/",
        "de/esolutions/fw/util/commons/"))
    outputJar.set(layout.buildDirectory.file("host-stock/stock-host.jar"))
}

dependencies {
    testImplementation(files(hostStockJar))
}

// jxe2jar code has no stack maps (and dead padding after returns), so HotSpot rejects much of it even repaired. The
// UI tests construct the stock Car activator, bank and models: skip verification of classpath classes in this
// test JVM only (the car's J9 runs with -noverify anyway; LinkCheck still checks every car artifact).
tasks.named<Test>("test") {
    jvmArgs("-XX:+UnlockDiagnosticVMOptions", "-XX:-BytecodeVerificationRemote")
}

if (!stockPresent) {
    // Nothing here compiles without the stock interfaces: skip the module (tests included) loudly, but never let
    // a car jar be produced from nothing.
    logger.warn("SKIPPED :adapter-hqc (compile, test, checks) — $stockMissingMessage")
    tasks.matching { it.name != "carJar" }.configureEach { enabled = false }
    tasks.named("carJar") {
        doFirst { throw GradleException(stockMissingMessage) }
    }
}
