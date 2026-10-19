package mibsdk.adapter.hqc;

import java.util.Hashtable;

/**
 * The original (unwrapped) DSI service objects, by interface name, recorded by {@link ConfiguratorCore} whenever
 * it registers a {@link DsiServiceProxy} in their place. The SDK's own bridges talk to these directly: whatever
 * the OSGi registry holds on top (our proxy, and with DSITracer active a stock trace proxy over ours, which no
 * one can see through) is bypassed, so the SDK's listener registrations and writes are never hook calls, never
 * vetoable, and never traced twice.
 */
final class RawServices {

    /** Interface name to raw service. Hashtable: its monitor is held only for one get or put. */
    private static final Hashtable RAW = new Hashtable();

    private RawServices() {
    }

    /** Called on the stock registration thread. Never throws. */
    static void record(String iface, Object raw) {
        try {
            if (iface != null && raw != null) {
                RAW.put(iface, raw);
            }
        } catch (Throwable ignored) {
            // the bridges fall back to the registry object
        }
    }

    /** The recorded raw service for {@code iface}; null if none was wrapped. */
    static Object get(String iface) {
        return iface == null ? null : RAW.get(iface);
    }

    /**
     * The object the SDK should call for {@code iface}: the recorded raw service, else {@code found} (from the
     * registry) with our own proxy peeled off if it is one.
     */
    static Object resolve(String iface, Object found) {
        Object raw = get(iface);
        return raw != null ? raw : DsiServiceProxy.unwrap(found);
    }

    /** Tests. */
    static void clear() {
        RAW.clear();
    }
}
