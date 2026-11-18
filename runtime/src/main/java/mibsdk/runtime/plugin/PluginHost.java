package mibsdk.runtime.plugin;

import java.io.File;
import mibsdk.api.MibPlugin;
import mibsdk.runtime.SdkRuntime;
import mibsdk.runtime.health.FailureTracker;
import mibsdk.runtime.plugin.manifest.PluginManifest;
import mibsdk.runtime.util.SerialExecutor;

/**
 * Everything the runtime holds for one discovered plugin. One instance per load: a reload replaces the
 * host. {@code state}/{@code reason}/{@code active} are guarded by the owning {@link SdkRuntime}; the live
 * resources are created on the runtime's control thread before the plugin's executor starts.
 */
public final class PluginHost {

    public final String id;
    public final File jar;
    /** Null when plugin.json could not be read (BLOCKED_INVALID). Replaced by the snapshot's copy on enable. */
    public volatile PluginManifest manifest;

    public PluginState state; // guarded by SdkRuntime.this
    public String reason; // guarded by SdkRuntime.this
    /** True from executor creation until disable starts; a disable or quarantine runs at most once. */
    public boolean active; // guarded by SdkRuntime.this

    public File snapshot;
    public FailureTracker failures;
    public Registrations registrations;
    public SerialExecutor exec;
    public PluginStorage storage;
    public PluginScheduler scheduler;
    public PluginContextImpl ctx;
    public volatile MibPlugin plugin;
    /** Set by the watchdog when a task on this plugin's executor exceeded hungMs. */
    public volatile boolean hung;

    public PluginHost(String id, File jar, PluginManifest manifest, PluginState state, String reason) {
        this.id = id;
        this.jar = jar;
        this.manifest = manifest;
        this.state = state;
        this.reason = reason;
    }

    public String version() {
        PluginManifest m = manifest;
        return m == null ? null : m.version();
    }
}
