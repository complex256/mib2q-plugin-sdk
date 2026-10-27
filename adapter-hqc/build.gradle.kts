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
// Read when a task needs them: an unset or missing -Pmibsdk.stockLsdJar / -Pmibsdk.stockDsiTracerJar fails that task
// with a message naming the property, and the rest of the build is unaffected.
val stockJarFiles = Toolchain.stockJars(project)

dependencies {
    implementation(project(":runtime"))
    compileOnly(stockJarFiles)
}

mibsdk {
    carClasspath.from(stockJarFiles)
    carClasspath.from(project(":api").tasks.named("carJar"))
    carClasspath.from(project(":compat").tasks.named("carJar"))
    carClasspath.from(project(":platform").tasks.named("carJar"))
    carClasspath.from(project(":runtime").tasks.named("carJar"))
}

// Raw jxe2jar classes do not load on a desktop JVM (ClassFormatError); tests use a repaired copy of just the
// stock packages they touch. The raw stock jars stay compileOnly, so they never reach the test runtime.
val hostStockJar = tasks.register<HostStockJarTask>("hostStockJar") {
    description = "Host-loadable copy of the stock classes the adapter tests need (test classpath only)."
    stockJars.from(stockJarFiles)
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
        // The System activator and factory (HMISystem= takeover); also the status popup's label font, popup 65's.
        "de/audi/tghu/system/hmi/",
        "de/audi/tghu/hmi/",
        // The speller screen's field and band fonts (Connectivity's FontFactory, F11).
        "de/audi/tghu/connectivity/",
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
