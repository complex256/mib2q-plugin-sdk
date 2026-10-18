package mibsdk.runtime;

/** Lifecycle state of one plugin as reported by {@link SdkRuntime#statuses()}. */
public enum PluginState {
    /** plugin.json read and valid; not yet enabled. */
    DISCOVERED,
    /** Unreadable jar, invalid plugin.json, or a jar not named {@code <id>.jar}. */
    BLOCKED_INVALID,
    /** Not every requested permission is granted (enforced from Task 10). */
    BLOCKED_PERMISSIONS,
    /** A dependency is missing, blocked, not enabled, or part of a cycle. */
    BLOCKED_DEPENDENCY,
    ENABLED,
    /** Loading the main class or {@code onEnable} threw; the plugin was disabled immediately. */
    FAILED,
    /** Too many callback failures in the window, or a hung callback; the plugin was disabled. */
    QUARANTINED,
    /** Disabled on request (reload, stop, Plugins menu). */
    DISABLED
}
