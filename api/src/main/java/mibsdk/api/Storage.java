package mibsdk.api;

/** Per-plugin persistent key/value storage. */
public interface Storage {

    String get(String key, String def);

    void put(String key, String value);

    void remove(String key);
}
