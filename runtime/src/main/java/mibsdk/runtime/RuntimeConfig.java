package mibsdk.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Runtime settings from {@code <baseDir>/sdk.properties}. A missing file, a missing key, or a value that
 * does not parse or is out of range falls back to the default. Keys:
 * <pre>
 * plugin.start.delay.ms             30000   delay before plugins load (applied from Task 12)
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
 * heap.low.bytes                  8388608
 * heap.low.checks                       3
 * heap.check.ms                      5000
 * log.file                /tmp/mibsdk.log
 * </pre>
 * Never put these in {@code bundles.properties}: every unknown key there is a bundle definition.
 */
public final class RuntimeConfig {

    public static final String FILE_NAME = "sdk.properties";
    private static final String DEFAULT_HOOK_SERVICE = "org.dsi.ifc.carparkingsystem.DSICarParkingSystem";
    private static final String DEFAULT_LOG_FILE = "/tmp/mibsdk.log";

    private final File baseDir;
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

    private RuntimeConfig(File baseDir, Properties p) {
        this.baseDir = baseDir;
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
    }

    /** Reads {@code baseDir/sdk.properties}; never throws for a missing or unreadable file. */
    public static RuntimeConfig load(File baseDir) {
        Properties p = new Properties();
        File f = new File(baseDir, FILE_NAME);
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
        return new RuntimeConfig(baseDir, p);
    }

    public File baseDir() {
        return baseDir;
    }

    public File pluginsDir() {
        return new File(baseDir, "plugins");
    }

    public File grantsFile() {
        return new File(baseDir, "grants.properties");
    }

    public File dataDir() {
        return new File(baseDir, "data");
    }

    public File cacheDir() {
        return new File(baseDir, "cache");
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
