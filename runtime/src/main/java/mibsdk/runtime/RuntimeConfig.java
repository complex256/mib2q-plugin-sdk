package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import mibsdk.platform.SdkRoots;

/**
 * Runtime settings from {@code <install>/sdk.properties}, plus where every SDK file lives (from the
 * {@link SdkRoots} the platform side supplies; the runtime names no on-unit path itself). A missing file, a missing key, or a value that
 * does not parse or is out of range falls back to the default. Keys:
 * <pre>
 * plugin.start.delay.ms             30000   delay (on the Clock) before plugins load; 0 loads during start()
 * tick.ms                            1000   MonotonicClock tick
 * callback.slow.ms                   5000   plugin task duration logged as slow
 * callback.hung.ms                  30000   plugin task duration that quarantines
 * quarantine.failures                   3   callback failures ...
 * quarantine.window.ms              60000   ... within this window quarantine
 * safe.mode.after.unhealthy.boots       3
 * healthy.after.ms                  60000
 * chain.configurator               (none)   configurator class to chain to
 * hook.services   org.dsi.ifc.carparkingsystem.DSICarParkingSystem   comma-separated
 * dev.poll.ms                        2000
 * heap.low.bytes                  8388608   0 disables the heap watch
 * heap.low.checks                       3
 * heap.check.ms                      5000
 * log.file                /tmp/mibsdk.log
 * storage.plugin.max.bytes         262144   one plugin's data file (estimated size); a put beyond fails
 * storage.total.max.bytes         1048576   every plugin's data file together (the persist partition is shared)
 * ui.publish.min.ms                   100   minimum interval between two published UI snapshots
 * ui.status.max.chars                  24   status-line length (indicators, then plugin items)
 * </pre>
 * Files: read-only at runtime, under {@link SdkRoots#install()}: sdk.properties, grants.properties, SAFE_MODE,
 * plugins/; writable and persistent, under {@link SdkRoots#persist()}: boot.state, ledger.properties, data/,
 * plugins.properties (the user's on/off choices); in RAM, under {@link SdkRoots#ram()}: cache/.
 * Never put these in {@code bundles.properties}: every unknown key there is a bundle definition.
 */
public final class RuntimeConfig {

    public static final String FILE_NAME = "sdk.properties";
    private static final String DEFAULT_HOOK_SERVICE = "org.dsi.ifc.carparkingsystem.DSICarParkingSystem";
    private static final String DEFAULT_LOG_FILE = "/tmp/mibsdk.log";

    private final SdkRoots roots;
    private final File logFile;
    private final long pluginStartDelayMs;
    private final long tickMs;
    private final long slowMs;
    private final long hungMs;
    private final int quarantineFailures;
    private final long quarantineWindowMs;
    private final int safeModeAfterUnhealthyBoots;
    private final long healthyAfterMs;
    private final String chainConfigurator;
    private final List<String> hookServices;
    private final long devPollMs;
    private final long heapLowBytes;
    private final int heapLowChecks;
    private final long heapCheckMs;
    private final long storagePluginMaxBytes;
    private final long storageTotalMaxBytes;
    private final long uiPublishMinMs;
    private final int uiStatusMaxChars;

    private RuntimeConfig(SdkRoots roots, Properties p) {
        this.roots = roots;
        String log = trimmed(p, "log.file");
        this.logFile = new File(log == null || log.length() == 0 ? DEFAULT_LOG_FILE : log);
        this.pluginStartDelayMs = longValue(p, "plugin.start.delay.ms", 30000, 0);
        this.tickMs = longValue(p, "tick.ms", 1000, 1);
        this.slowMs = longValue(p, "callback.slow.ms", 5000, 1);
        this.hungMs = longValue(p, "callback.hung.ms", 30000, 1);
        this.quarantineFailures = (int) longValue(p, "quarantine.failures", 3, 1);
        this.quarantineWindowMs = longValue(p, "quarantine.window.ms", 60000, 1);
        this.safeModeAfterUnhealthyBoots = (int) longValue(p, "safe.mode.after.unhealthy.boots", 3, 1);
        this.healthyAfterMs = longValue(p, "healthy.after.ms", 60000, 0);
        String chain = trimmed(p, "chain.configurator");
        this.chainConfigurator = chain == null || chain.length() == 0 ? null : chain;
        String hooks = p.getProperty("hook.services");
        this.hookServices = hooks == null ? Collections.singletonList(DEFAULT_HOOK_SERVICE) : commaList(hooks);
        this.devPollMs = longValue(p, "dev.poll.ms", 2000, 1);
        this.heapLowBytes = longValue(p, "heap.low.bytes", 8388608, 0);
        this.heapLowChecks = (int) longValue(p, "heap.low.checks", 3, 1);
        this.heapCheckMs = longValue(p, "heap.check.ms", 5000, 1);
        this.storagePluginMaxBytes = longValue(p, "storage.plugin.max.bytes", 262144, 1);
        this.storageTotalMaxBytes = longValue(p, "storage.total.max.bytes", 1048576, 1);
        this.uiPublishMinMs = longValue(p, "ui.publish.min.ms", 100, 0);
        this.uiStatusMaxChars = (int) longValue(p, "ui.status.max.chars", 24, 4);
    }

    /** Reads {@code <install>/sdk.properties}; never throws for a missing or unreadable file. */
    public static RuntimeConfig load(SdkRoots roots) {
        Properties p = new Properties();
        File f = new File(roots.install(), FILE_NAME);
        if (f.isFile()) {
            InputStream in = null;
            try {
                in = new FileInputStream(f);
                p.load(in);
            } catch (IOException e) {
                p = new Properties(); // a half-read file is no better than none
            } catch (IllegalArgumentException e) {
                p = new Properties(); // malformed unicode escape
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // nothing to do
                    }
                }
            }
        }
        return new RuntimeConfig(roots, p);
    }

    public SdkRoots roots() {
        return roots;
    }

    /** Read-only: plugin jars and the reload markers the installer drops next to them. */
    public File pluginsDir() {
        return new File(roots.install(), "plugins");
    }

    /** Read-only. */
    public File grantsFile() {
        return new File(roots.install(), "grants.properties");
    }

    /** Persistent: {@code <id>.properties} per plugin. */
    public File dataDir() {
        return new File(roots.persist(), "data");
    }

    /** RAM: per-boot jar snapshots. */
    public File cacheDir() {
        return new File(roots.ram(), "cache");
    }

    /** Persistent: the boot guard's crash counter ({@link BootGuard}). */
    public File bootStateFile() {
        return new File(roots.persist(), "boot.state");
    }

    /** Persistent: the override ledger ({@link OverrideLedger}). */
    public File ledgerFile() {
        return new File(roots.persist(), "ledger.properties");
    }

    /** Persistent: the user's on/off choices from the Plugins menu ({@link UserToggles}). */
    public File userTogglesFile() {
        return new File(roots.persist(), "plugins.properties");
    }

    /** Read-only: marker file that forces safe mode while it exists. */
    public File safeModeMarker() {
        return new File(roots.install(), "SAFE_MODE");
    }

    public File logFile() {
        return logFile;
    }

    public long pluginStartDelayMs() {
        return pluginStartDelayMs;
    }

    public long tickMs() {
        return tickMs;
    }

    public long slowMs() {
        return slowMs;
    }

    public long hungMs() {
        return hungMs;
    }

    public int quarantineFailures() {
        return quarantineFailures;
    }

    public long quarantineWindowMs() {
        return quarantineWindowMs;
    }

    public int safeModeAfterUnhealthyBoots() {
        return safeModeAfterUnhealthyBoots;
    }

    public long healthyAfterMs() {
        return healthyAfterMs;
    }

    /** Null if absent. */
    public String chainConfigurator() {
        return chainConfigurator;
    }

    /** Unmodifiable. */
    public List<String> hookServices() {
        return hookServices;
    }

    public long devPollMs() {
        return devPollMs;
    }

    public long heapLowBytes() {
        return heapLowBytes;
    }

    public int heapLowChecks() {
        return heapLowChecks;
    }

    public long heapCheckMs() {
        return heapCheckMs;
    }

    public long storagePluginMaxBytes() {
        return storagePluginMaxBytes;
    }

    /** Minimum interval between two published UI snapshots ({@code ui.publish.min.ms}); 0 publishes every change. */
    public long uiPublishMinMs() {
        return uiPublishMinMs;
    }

    /** Status-line length in characters ({@code ui.status.max.chars}, at least 4). */
    public int uiStatusMaxChars() {
        return uiStatusMaxChars;
    }

    public long storageTotalMaxBytes() {
        return storageTotalMaxBytes;
    }

    private static String trimmed(Properties p, String key) {
        String v = p.getProperty(key);
        return v == null ? null : v.trim();
    }

    /** The value if it parses and is in [min, Integer.MAX_VALUE]; otherwise {@code def}. */
    private static long longValue(Properties p, String key, long def, long min) {
        String v = trimmed(p, key);
        if (v == null) {
            return def;
        }
        try {
            long n = Long.parseLong(v);
            return n < min || n > Integer.MAX_VALUE ? def : n;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Splits on commas (no String.split on the car), trims, drops empty items. */
    private static List<String> commaList(String s) {
        List<String> out = new ArrayList<String>();
        int start = 0;
        while (start <= s.length()) {
            int comma = s.indexOf(',', start);
            int end = comma < 0 ? s.length() : comma;
            String item = s.substring(start, end).trim();
            if (item.length() > 0) {
                out.add(item);
            }
            start = end + 1;
        }
        return Collections.unmodifiableList(out);
    }
}
