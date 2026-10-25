package mibsdk.platform;

/**
 * An icon on a Car row or a status item: one of the SDK's built-in icons ({@link #STOCK}, by name) or an installed
 * package PNG ({@link #FILE}, by owner and content key). Immutable. Two icons are equal when they are the same stock
 * name, or the same owner's file with the same content key, drawn the same way ({@link #ownColours}) and with an
 * equal {@link #reflection}: identical bytes in two packages are two icons. The colour choice is not content:
 * {@link #key} and {@link #owner} ignore it.
 */
public final class UiIcon {

    public static final int STOCK = 0;
    public static final int FILE = 1;

    /** The owner of the built-in icons: the plugin id reserved for the SDK. */
    private static final String SDK_OWNER = "mibsdk";

    private final int kind;
    private final String name;
    private final String owner;
    private final String path;
    private final String key;
    private final int width;
    private final int height;
    private final boolean ownColours;
    private final UiIcon reflection;

    private UiIcon(int kind, String name, String owner, String path, String key, int width, int height,
            boolean ownColours, UiIcon reflection) {
        this.kind = kind;
        this.name = name;
        this.owner = owner;
        this.path = path;
        this.key = key;
        this.width = width;
        this.height = height;
        this.ownColours = ownColours;
        this.reflection = reflection;
    }

    /** A built-in icon by name (a {@code mibsdk.api.StockIcons} constant). Its owner is {@code mibsdk}. */
    public static UiIcon stock(String name) {
        return new UiIcon(STOCK, name, SDK_OWNER, null, null, 0, 0, false, null);
    }

    /**
     * An installed PNG.
     *
     * @param owner the plugin id, or {@code mibsdk} for the built-in set
     * @param path the file, {@code <dir>/<key>.png}
     * @param key the file's content key ({@code <sha16>})
     */
    public static UiIcon file(String owner, String path, String key, int width, int height) {
        return new UiIcon(FILE, null, owner, path, key, width, height, false, null);
    }

    public int kind() {
        return kind;
    }

    /** The stock name; null for a file. */
    public String name() {
        return name;
    }

    public String owner() {
        return owner;
    }

    /** The PNG file; null for a stock icon. */
    public String path() {
        return path;
    }

    /** The content key ({@code <sha16>}); null for a stock icon. */
    public String key() {
        return key;
    }

    /** In pixels; 0 for a stock icon (drawn at the size of its slot). */
    public int width() {
        return width;
    }

    /** In pixels; 0 for a stock icon. */
    public int height() {
        return height;
    }

    /** True to draw the PNG in its own colours; false (the default) to tint it like the stock icons. */
    public boolean ownColours() {
        return ownColours;
    }

    /**
     * This icon, drawn in its own colours or tinted, with the same reflection; the same object if that is already so.
     */
    public UiIcon withOwnColours(boolean own) {
        return own == ownColours ? this : new UiIcon(kind, name, owner, path, key, width, height, own, reflection);
    }

    /**
     * The package's faded drawer reflection of this icon (the index's {@code <name>.r} line): a {@link #FILE} icon
     * with the same owner. Null if the package has none, and always for a stock icon.
     */
    public UiIcon reflection() {
        return reflection;
    }

    /** This icon with reflection {@code r} (null for none). */
    public UiIcon withReflection(UiIcon r) {
        return new UiIcon(kind, name, owner, path, key, width, height, ownColours, r);
    }

    public boolean equals(Object o) {
        if (!(o instanceof UiIcon)) {
            return false;
        }
        UiIcon i = (UiIcon) o;
        return kind == i.kind && ownColours == i.ownColours && same(reflection, i.reflection)
                && (kind == STOCK ? same(name, i.name) : same(owner, i.owner) && same(key, i.key));
    }

    public int hashCode() {
        return (kind == STOCK ? hash(name) : 31 * hash(owner) + hash(key) + 1) + (ownColours ? 7 : 0)
                + (reflection == null ? 0 : 17 * reflection.hashCode());
    }

    public String toString() {
        return (kind == STOCK ? "stock:" + name : owner + "/" + key) + (ownColours ? "+own" : "")
                + (reflection == null ? "" : "+r:" + reflection.key());
    }

    private static boolean same(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static int hash(String s) {
        return s == null ? 0 : s.hashCode();
    }
}
