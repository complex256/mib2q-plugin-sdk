package mibsdk.runtime;

/** Immutable snapshot of one plugin's state. */
public final class PluginStatus {

    private final String id;
    private final String version;
    private final PluginState state;
    private final String reason;

    PluginStatus(String id, String version, PluginState state, String reason) {
        this.id = id;
        this.version = version;
        this.state = state;
        this.reason = reason;
    }

    /** The manifest id, or the jar's base name when plugin.json could not be read. */
    public String id() {
        return id;
    }

    /** The manifest version; null when plugin.json could not be read. */
    public String version() {
        return version;
    }

    public PluginState state() {
        return state;
    }

    public String reason() {
        return reason;
    }

    public String toString() {
        return id + " " + version + " " + state + " " + reason;
    }
}
