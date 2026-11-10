package mibsdk.api;

/**
 * The names of the SDK's built-in icons, for {@link Ui#stockIcon(String)}.
 *
 * <p>Built-in icons need no file and are always small enough for a Car row, a page item or a status item.
 *
 * <p>The second group is the car's own ring set: the glyphs of the 3D ring the main menu uses. They are drawn by a
 * {@link PageSpec#ring} page; on a Car row, a list row or a status item they show without an icon (the ring glyphs
 * live in the HMI's 3D atlas, not in a PNG). Use them for a ring page's items, and the shapes above elsewhere.
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

    // ---- the ring set (api 8): the 3D ring's own glyphs ----

    /** A car, the main menu's Vehicle. @since api 8 */
    public static final String CAR = "car";
    /** A radio mast. @since api 8 */
    public static final String RADIO = "radio";
    /** A disc, media. @since api 8 */
    public static final String MEDIA = "media";
    /** A handset, telephone. @since api 8 */
    public static final String PHONE = "phone";
    /** A navigation flag. @since api 8 */
    public static final String NAVIGATION = "navigation";
    /** A map marker. @since api 8 */
    public static final String MAP = "map";
    /** A globe, connectivity. @since api 8 */
    public static final String GLOBE = "globe";
    /** A document, office. @since api 8 */
    public static final String OFFICE = "office";
    /** A speaker, sound. @since api 8 */
    public static final String SOUND = "sound";
    /** Gear wheels, settings. @since api 8 */
    public static final String SETTINGS = "settings";
    /** A warning triangle. @since api 8 */
    public static final String WARNING = "warning";
    /** A night-vision eye. @since api 8 */
    public static final String NIGHT_VISION = "night_vision";
    /** A headset. @since api 8 */
    public static final String HEADSET = "headset";
    /** A service wrench with rings. @since api 8 */
    public static final String SERVICE = "service";
    /** The Audi four-ring flower. @since api 8 */
    public static final String FLOWER = "flower";
    /** Traffic information arrows. @since api 8 */
    public static final String TRAFFIC = "traffic";
    /** A wrench, plugins and tools. @since api 8 */
    public static final String WRENCH = "wrench";
    /** A smartphone. @since api 8 */
    public static final String SMARTPHONE = "smartphone";

    private static final String[] ALL = {DOT, CIRCLE, STAR, TRIANGLE, SQUARE, CHECK, CROSS, PLUG,
            CAR, RADIO, MEDIA, PHONE, NAVIGATION, MAP, GLOBE, OFFICE, SOUND, SETTINGS, WARNING, NIGHT_VISION,
            HEADSET, SERVICE, FLOWER, TRAFFIC, WRENCH, SMARTPHONE};

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
