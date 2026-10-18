package mibsdk.runtime;

/** Thrown when plugin.json is malformed or fails validation. */
public final class ManifestException extends Exception {

    public ManifestException(String message) {
        super(message);
    }
}
