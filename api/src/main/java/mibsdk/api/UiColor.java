package mibsdk.api;

/**
 * A packed ARGB colour for a status item's text. Immutable.
 *
 * <p>Without a colour the built-in bar draws an item in its own colour (white, or the app palette), like the stock
 * items. A colour set here is global: it applies in every HMI context and does not follow the palette. Only the text
 * takes it; an item's icon keeps its own colour rules ({@link Icon#OWN_COLOURS}).
 */
public final class UiColor {

    private final int argb;

    private UiColor(int argb) {
        this.argb = argb;
    }

    /**
     * An opaque colour: red, green and blue, each 0..255.
     *
     * @since api 6
     */
    public static UiColor rgb(int r, int g, int b) {
        return rgba(r, g, b, 255);
    }

    /**
     * A colour with alpha: red, green, blue and alpha, each 0..255.
     *
     * @throws IllegalArgumentException if a component is outside 0..255
     * @since api 6
     */
    public static UiColor rgba(int r, int g, int b, int a) {
        check(r, "r");
        check(g, "g");
        check(b, "b");
        check(a, "a");
        return new UiColor((a << 24) | (r << 16) | (g << 8) | b);
    }

    /**
     * The colour packed as {@code 0xAARRGGBB}.
     *
     * @since api 6
     */
    public static UiColor of(int argb) {
        return new UiColor(argb);
    }

    /**
     * The packed {@code 0xAARRGGBB} value.
     *
     * @since api 6
     */
    public int argb() {
        return argb;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UiColor && ((UiColor) o).argb == argb;
    }

    @Override
    public int hashCode() {
        return argb;
    }

    @Override
    public String toString() {
        String hex = Integer.toHexString(argb);
        return "UiColor{#" + "00000000".substring(hex.length()) + hex + "}";
    }

    private static void check(int value, String name) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException(name + " must be 0..255: " + value);
        }
    }
}
