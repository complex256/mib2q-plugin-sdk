package mibsdk.api;

/**
 * Per-plugin persistent key/value storage. Space is capped (by default 256 KiB per plugin and 1 MiB for all
 * plugins, set in sdk.properties): the unit's persistent partition is small and shared with the stock HMI.
 */
public interface Storage {

    String get(String key, String def);

    /**
     * Stores {@code value} (null removes the key).
     *
     * @throws IllegalStateException if the value would take this plugin's storage, or all plugins' storage, past
     *         its cap; nothing is stored. Removing keys or storing shorter values always works.
     */
    void put(String key, String value);

    void remove(String key);
}
