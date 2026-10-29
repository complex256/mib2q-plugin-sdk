package mibsdk.api;

/**
 * The names of the SDK's built-in icons, for {@link Ui#stockIcon(String)}.
 *
 * <p>Built-in icons need no file and are always small enough for a Car row, a page item or a status item.
 *
 */
public final class StockIcons {

    /** A small filled dot. */
    public static final String DOT = "dot";
    /** An outlined circle. */
    public static final String CIRCLE = "circle";
    /** A star. */
    public static final String STAR = "star";
    /** A triangle. */
    public static final String TRIANGLE = "triangle";
    /** A square. */
    public static final String SQUARE = "square";
    /** A check mark. */
    public static final String CHECK = "check";
    /** A cross. */
    public static final String CROSS = "cross";
    /** A plug. */
    public static final String PLUG = "plug";

    private static final String[] ALL = {DOT, CIRCLE, STAR, TRIANGLE, SQUARE, CHECK, CROSS, PLUG};

    private StockIcons() {
    }

    /**
     * Whether {@code name} is one of the built-in icons.
     *
     * @param name the name; null is not known
     * @return true for a built-in name
     * @since api 3
     */
    public static boolean isKnown(String name) {
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].equals(name)) {
                return true;
            }
        }
        return false;
    }
}
