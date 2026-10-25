package mibsdk.api;

/**
 * An icon for a Car row or a status item. Opaque: get one from {@link Ui#icon(String)}, {@link Ui#icon(String, int)} or
 * {@link Ui#stockIcon(String)}
 * and pass it back to the same plugin's {@link Ui}. Only handles made by the runtime for this plugin are accepted; any
 * other {@code Icon} is rejected with {@link IllegalArgumentException}.
 */
public interface Icon {

    /**
     * Drawn in the app colour, like the stock icons (which are white glyphs). The default.
     *
     * @since api 4
     */
    int TINTED = 0;

    /**
     * Drawn in the PNG's own colours.
     *
     * @since api 4
     */
    int OWN_COLOURS = 1;
}
