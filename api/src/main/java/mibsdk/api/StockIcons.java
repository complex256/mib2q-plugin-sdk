package mibsdk.api;

/** The names of the SDK's built-in icons, for {@link Ui#stockIcon(String)}. */
public final class StockIcons {

    public static final String DOT = "dot";
    public static final String CIRCLE = "circle";
    public static final String STAR = "star";
    public static final String TRIANGLE = "triangle";
    public static final String SQUARE = "square";
    public static final String CHECK = "check";
    public static final String CROSS = "cross";
    public static final String PLUG = "plug";

    private static final String[] ALL = {DOT, CIRCLE, STAR, TRIANGLE, SQUARE, CHECK, CROSS, PLUG};

    private StockIcons() {
    }

    /** Whether {@code name} is one of the built-in icons; false for null. */
    public static boolean isKnown(String name) {
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].equals(name)) {
                return true;
            }
        }
        return false;
    }
}
