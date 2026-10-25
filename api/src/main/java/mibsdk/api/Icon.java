package mibsdk.api;

/**
 * An icon for a Car row or a status item. Opaque: get one from {@link Ui#icon(String)} or {@link Ui#stockIcon(String)}
 * and pass it back to the same plugin's {@link Ui}. Only handles made by the runtime for this plugin are accepted; any
 * other {@code Icon} is rejected with {@link IllegalArgumentException}.
 */
public interface Icon {
}
