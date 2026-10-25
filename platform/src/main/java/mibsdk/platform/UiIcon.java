package mibsdk.platform;

/**
 * An icon on a Car row or a status item: one of the SDK's built-in icons ({@link #STOCK}, by name) or an installed
 * package PNG ({@link #FILE}, by owner and content key). Immutable. Two icons are equal when they are the same stock
 * name, or the same owner's file with the same content key: identical bytes in two packages are two icons.
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

    private UiIcon(int kind, String name, String owner, String path, String key, int width, int height) {
        this.kind = kind;
        this.name = name;
        this.owner = owner;
        this.path = path;
        this.key = key;
        this.width = width;
        this.height = height;
    }

    /** A built-in icon by name (a {@code mibsdk.api.StockIcons} constant). Its owner is {@code mibsdk}. */
    public static UiIcon stock(String name) {
        return new UiIcon(STOCK, name, SDK_OWNER, null, null, 0, 0);
    }

    /**
     * An installed PNG.
     *
     * @param owner the plugin id, or {@code mibsdk} for the built-in set
     * @param path the file, {@code <dir>/<key>.png}
     * @param key the file's content key ({@code <sha16>})
     */
    public static UiIcon file(String owner, String path, String key, int width, int height) {
        return new UiIcon(FILE, null, owner, path, key, width, height);
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

    public boolean equals(Object o) {
        if (!(o instanceof UiIcon)) {
            return false;
        }
        UiIcon i = (UiIcon) o;
        return kind == i.kind && (kind == STOCK ? same(name, i.name) : same(owner, i.owner) && same(key, i.key));
    }

    public int hashCode() {
        return kind == STOCK ? hash(name) : 31 * hash(owner) + hash(key) + 1;
    }

    public String toString() {
        return kind == STOCK ? "stock:" + name : owner + "/" + key;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static int hash(String s) {
        return s == null ? 0 : s.hashCode();
    }
}
