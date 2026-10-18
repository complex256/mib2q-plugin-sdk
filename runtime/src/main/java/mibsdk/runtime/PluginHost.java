package mibsdk.runtime;

import java.io.File;
import mibsdk.api.MibPlugin;

/**
 * Everything the runtime holds for one discovered plugin. One instance per load: a reload replaces the
 * host. {@code state}/{@code reason}/{@code active} are guarded by the owning {@link SdkRuntime}; the live
 * resources are created on the runtime's control thread before the plugin's executor starts.
 */
final class PluginHost {

    final String id;
    final File jar;
    /** Null when plugin.json could not be read (BLOCKED_INVALID). Replaced by the snapshot's copy on enable. */
    volatile PluginManifest manifest;

    PluginState state; // guarded by SdkRuntime.this
    String reason; // guarded by SdkRuntime.this
    /** True from executor creation until disable starts; a disable or quarantine runs at most once. */
    boolean active; // guarded by SdkRuntime.this

    File snapshot;
    FailureTracker failures;
    Registrations registrations;
    SerialExecutor exec;
    PluginStorage storage;
    PluginScheduler scheduler;
    PluginContextImpl ctx;
    volatile MibPlugin plugin;
    /** Set by the watchdog when a task on this plugin's executor exceeded hungMs. */
    volatile boolean hung;

    PluginHost(String id, File jar, PluginManifest manifest, PluginState state, String reason) {
        this.id = id;
        this.jar = jar;
        this.manifest = manifest;
        this.state = state;
        this.reason = reason;
    }

    String version() {
        PluginManifest m = manifest;
        return m == null ? null : m.version();
    }
}
