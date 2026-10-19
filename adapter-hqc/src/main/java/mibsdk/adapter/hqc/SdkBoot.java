package mibsdk.adapter.hqc;

import java.io.File;
import mibsdk.platform.PlatformAdapter;
import mibsdk.runtime.AdapterSelector;
import mibsdk.runtime.MonotonicClock;
import mibsdk.runtime.RuntimeConfig;
import mibsdk.runtime.SdkRuntime;
import org.osgi.framework.BundleContext;

/**
 * The work behind {@link SdkBundleActivator}, only ever run on SDK threads ({@code mibsdk-main} builds and starts
 * it, {@code mibsdk-stop} stops it): config, clock, adapter selection and the {@link SdkRuntime}.
 */
final class SdkBoot {

    private final MonotonicClock clock;
    private final SdkRuntime runtime;

    private SdkBoot(MonotonicClock clock, SdkRuntime runtime) {
        this.clock = clock;
        this.runtime = runtime;
    }

    /** Builds everything; starts nothing except the clock's ticker. */
    static SdkBoot create(BundleContext ctx, File baseDir, File versionInfo) {
        RuntimeConfig cfg = RuntimeConfig.load(baseDir);
        String hmi = AdapterSelector.readHmiVersion(versionInfo);
        MonotonicClock clock = new MonotonicClock(new MonotonicClock.WallSource() {
            public long nowMs() {
                return System.currentTimeMillis();
            }
        }, cfg.tickMs());
        clock.start();
        PlatformAdapter adapter = AdapterSelector.select(hmi, new PlatformAdapter[] {new HqcPlatform(ctx, clock)});
        SdkGlobals.LOG.append("INFO", "mibsdk.hqc", "activator: hmi=" + hmi + " adapter="
                + (adapter == null ? "none" : adapter.id()));
        return new SdkBoot(clock, new SdkRuntime(cfg, adapter, clock, SdkGlobals.HOOKS, SdkGlobals.LOG));
    }

    /** {@link SdkRuntime#start()}: may block up to about 2 x hungMs. */
    void start() {
        runtime.start();
    }

    /** {@link SdkRuntime#stop()} (may block up to about 2 x hungMs), then the clock. */
    void stop() {
        try {
            runtime.stop();
        } finally {
            clock.stop();
        }
    }

    /** Built but never started: only the clock runs. */
    void discard() {
        clock.stop();
    }
}
