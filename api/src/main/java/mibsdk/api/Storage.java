package mibsdk.api;

/**
 * Per-plugin persistent key/value storage. Obtain it from {@link PluginContext#storage()}.
 *
 * <p>Space is capped by default at 256 KiB per plugin and 1 MiB for all plugins; the unit's persistent
 * partition is small and shared with the stock HMI. Values are strings, so parse them defensively: they
 * may come from an older version of your plugin.
 *
 * @since api 1
 */
public interface Storage {

    /**
     * Reads a value.
     *
     * @param key the key
     * @param def returned when the key is absent
     * @return the value, or {@code def}
     */
    String get(String key, String def);

    /**
     * Stores a value, or removes the key when {@code value} is null.
     *
     * @param key the key
     * @param value the value, or null to remove
     * @throws IllegalStateException if the value would take this plugin, or all plugins, past its cap;
     *         nothing is stored
     */
    void put(String key, String value);

    /**
     * Removes a key.
     *
     * @param key the key
     */
    void remove(String key);
}
