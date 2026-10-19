package mibsdk.adapter.hqc;

import java.io.File;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * The SDK bundle's activator, called by the stock LSD OSGi kernel on its own thread at boot.
 *
 * <p>{@link #start} only spawns the daemon thread {@code mibsdk-main} and returns (well under 50 ms); that thread
 * reads sdk.properties and the HMI version, selects the adapter ({@link HqcPlatform} or none), builds the clock
 * and the {@code SdkRuntime} and starts it. {@link #stop} hands the runtime's stop to a daemon thread
 * {@code mibsdk-stop} (which first lets {@code mibsdk-main} finish booting, so start and stop never overlap) and
 * waits for it at most {@link #STOP_WAIT_MS}; after that LSD gets its thread back and the stop finishes in the
 * background. A stop before the runtime exists means it is never started.
 *
 * <p>Like {@link ChainingConfigurator}, this class is a shell whose fields, signatures and LSD-thread code use only
 * JCL and stock types: every SDK class is first touched on {@code mibsdk-main} or {@code mibsdk-stop}, inside a
 * catch-all, so a partial install cannot throw out of {@code start} or {@code stop}. Neither ever throws. One
 * lifecycle per instance: a second {@code start} is ignored.
 */
public final class SdkBundleActivator implements BundleActivator {

    /** How long {@link #stop} holds LSD's thread. */
    static final long STOP_WAIT_MS = 2000;

    private final File baseDir;
    private final File versionInfo;
    private final Object lock = new Object();
    private Thread main; // guarded by lock
    private Object boot; // guarded by lock; an SdkBoot, typed Object so this class needs no SDK classes
    private boolean stopRequested; // guarded by lock

    /** Used by the OSGi kernel: the unit's paths (inlined constants). */
    public SdkBundleActivator() {
        this(new File(SdkFiles.BASE), new File(SdkFiles.VERSION_INFO));
    }

    /** Tests. Stores the paths only. */
    SdkBundleActivator(File baseDir, File versionInfo) {
        this.baseDir = baseDir;
        this.versionInfo = versionInfo;
    }

    public void start(BundleContext ctx) {
        try {
            Thread t = new MainThread(this, ctx);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            synchronized (lock) {
                if (main != null) {
                    return;
                }
                main = t;
            }
            t.start();
        } catch (Throwable t) {
            stderr("start failed, the SDK stays off: " + t);
        }
    }

    public void stop(BundleContext ctx) {
        try {
            Thread m;
            synchronized (lock) {
                if (main == null || stopRequested) {
                    return;
                }
                stopRequested = true;
                m = main;
            }
            Thread s = new StopThread(this, m);
            s.setDaemon(true);
            s.setPriority(Thread.NORM_PRIORITY - 1);
            s.start();
            s.join(STOP_WAIT_MS);
            if (s.isAlive()) {
                stderr("stop still running after " + STOP_WAIT_MS + " ms; it finishes in the background");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            stderr("stop failed: " + t);
        }
    }

    /** Test hook: the {@code mibsdk-main} thread, once started. */
    Thread mainThread() {
        synchronized (lock) {
            return main;
        }
    }

    /** Keeps the built runtime for stop; false if stop came first (the caller discards it). */
    private boolean keep(Object b) {
        synchronized (lock) {
            if (stopRequested) {
                return false;
            }
            boot = b;
            return true;
        }
    }

    private Object booted() {
        synchronized (lock) {
            return boot;
        }
    }

    private static void stderr(String msg) {
        try {
            System.err.println("mibsdk: " + msg);
        } catch (Throwable ignored) {
            // nothing left to do
        }
    }

    /** SDK threads report to stderr and, if it loads, the SDK log. */
    private static void report(String msg) {
        stderr(msg);
        try {
            SdkGlobals.LOG.append("ERROR", "mibsdk.hqc", msg);
        } catch (Throwable ignored) {
            // a partial install: stderr is all there is
        }
    }

    /** Builds and starts the runtime. */
    private static final class MainThread extends Thread {
        private final SdkBundleActivator owner;
        private final BundleContext ctx;

        MainThread(SdkBundleActivator owner, BundleContext ctx) {
            super("mibsdk-main");
            this.owner = owner;
            this.ctx = ctx;
        }

        public void run() {
            try {
                SdkBoot b = SdkBoot.create(ctx, owner.baseDir, owner.versionInfo);
                if (!owner.keep(b)) {
                    b.discard();
                    return;
                }
                b.start();
            } catch (Throwable t) {
                report("SDK boot failed, the SDK stays off: " + t);
            }
        }
    }

    /** Waits for the boot to finish, then stops the runtime. */
    private static final class StopThread extends Thread {
        private final SdkBundleActivator owner;
        private final Thread main;

        StopThread(SdkBundleActivator owner, Thread main) {
            super("mibsdk-stop");
            this.owner = owner;
            this.main = main;
        }

        public void run() {
            try {
                main.join();
                Object b = owner.booted();
                if (b != null) {
                    ((SdkBoot) b).stop();
                }
            } catch (Throwable t) {
                report("SDK stop failed: " + t);
            }
        }
    }
}
