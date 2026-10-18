package mibsdk.runtime;

import mibsdk.api.OverrideScope;

/** Immutable snapshot of one override the ledger holds (for the indicator UI and diagnostics). */
public final class ActiveOverride {

    private final String key;
    private final String pluginId;
    private final OverrideScope scope;
    private final String activeValue;

    ActiveOverride(String key, String pluginId, OverrideScope scope, String activeValue) {
        this.key = key;
        this.pluginId = pluginId;
        this.scope = scope;
        this.activeValue = activeValue;
    }

    public String key() {
        return key;
    }

    public String pluginId() {
        return pluginId;
    }

    public OverrideScope scope() {
        return scope;
    }

    /** The encoded value the override forces, e.g. {@code F1R0L1X1} for parking sound directions. */
    public String activeValue() {
        return activeValue;
    }

    @Override
    public String toString() {
        return key + "=" + activeValue + " by " + pluginId + " (" + scope + ")";
    }
}
