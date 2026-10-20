package mibsdk.runtime;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The adapter's features as the runtime last read them, shared by every plugin context: {@code ctx.has()}, hook
 * registration and {@code vehicle().parking()} read it at call time, so a feature the adapter learns after the
 * plugin load reaches plugins that are already running. Immutable snapshots behind a volatile reference.
 */
final class LiveFeatures {

    private volatile Set<String> current;

    LiveFeatures(Set<String> initial) {
        current = copy(initial);
    }

    boolean has(String feature) {
        return feature != null && current.contains(feature);
    }

    /** Unmodifiable. */
    Set<String> snapshot() {
        return current;
    }

    /** Replaces the set; true if it changed. Called on the runtime's control thread only. */
    boolean set(Set<String> next) {
        Set<String> n = copy(next);
        if (n.equals(current)) {
            return false;
        }
        current = n;
        return true;
    }

    private static Set<String> copy(Set<String> s) {
        return Collections.unmodifiableSet(new HashSet<String>(s));
    }
}
