package mibsdk.api;

public class OverrideConflictException extends Exception {

    private static final long serialVersionUID = 1L;

    public OverrideConflictException(String key, String holderPluginId) {
        super("override " + key + " is held by " + holderPluginId);
    }
}
