package mibsdk.api;

/**
 * Another plugin already holds the requested override.
 *
 * @since api 1
 */
public class OverrideConflictException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * @param key the override key
     * @param holderPluginId the plugin that holds it
     */
    public OverrideConflictException(String key, String holderPluginId) {
        super("override " + key + " is held by " + holderPluginId);
    }
}
