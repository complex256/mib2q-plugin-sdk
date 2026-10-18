package mibsdk.runtime;

import mibsdk.api.OverrideScope;

/** Immutable snapshot of one ledger entry whose effect may still be on the car (for the indicator UI). */
public final class ActiveOverride {

    private final String key;
    private final String pluginId;
    private final OverrideScope scope;
    private final String activeValue;
    private final boolean restorePending;

    ActiveOverride(String key, String pluginId, OverrideScope scope, String activeValue, boolean restorePending) {
        this.key = key;
        this.pluginId = pluginId;
        this.scope = scope;
        this.activeValue = activeValue;
        this.restorePending = restorePending;
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

    /**
     * True once the override has ended but its prior value is not back on the car yet (the restore failed
     * and is being retried). The indicator should keep showing it, e.g. as "restoring".
     */
    public boolean restorePending() {
        return restorePending;
    }

    @Override
    public String toString() {
        return key + "=" + activeValue + " by " + pluginId + " (" + scope + (restorePending ? ", restoring" : "") + ")";
    }
}
