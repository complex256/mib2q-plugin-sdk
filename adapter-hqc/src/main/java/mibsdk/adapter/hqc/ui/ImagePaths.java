package mibsdk.adapter.hqc.ui;

/**
 * Image paths as the HMI resolves them: relative to its ImageRoot ({@code lsd/images}). Pure String functions, no
 * java.io: called only on {@code mibsdk-ui}, when an icon id is given ({@link IconIds#idFor}), so that
 * {@code getImagePath} on HMIEvent is a lookup.
 */
final class ImagePaths {

    private ImagePaths() {
    }

    /** The HMI's ImageRoot for an SDK install root: its parent plus {@code /images}. */
    static String imageRoot(String installRoot) {
        String r = trimSlash(installRoot);
        int at = r.lastIndexOf('/');
        return (at <= 0 ? "" : r.substring(0, at)) + "/images";
    }

    /**
     * The {@code ../}-relative path from directory {@code imageRoot} to {@code target}; both are absolute and already
     * normalised. Null if either is not absolute.
     */
    static String relative(String imageRoot, String target) {
        if (imageRoot == null || target == null || !imageRoot.startsWith("/") || !target.startsWith("/")) {
            return null;
        }
        String[] from = split(trimSlash(imageRoot));
        String[] to = split(target);
        int common = 0;
        while (common < from.length && common < to.length - 1 && from[common].equals(to[common])) {
            common++;
        }
        StringBuffer b = new StringBuffer();
        for (int i = common; i < from.length; i++) {
            b.append("../");
        }
        for (int i = common; i < to.length; i++) {
            b.append(to[i]);
            if (i < to.length - 1) {
                b.append('/');
            }
        }
        return b.toString();
    }

    private static String trimSlash(String s) {
        return s.length() > 1 && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** The non-empty segments of an absolute path. */
    private static String[] split(String path) {
        int n = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) != '/' && (i == 0 || path.charAt(i - 1) == '/')) {
                n++;
            }
        }
        String[] out = new String[n];
        int k = 0;
        int start = -1;
        for (int i = 0; i <= path.length(); i++) {
            boolean sep = i == path.length() || path.charAt(i) == '/';
            if (sep && start >= 0) {
                out[k++] = path.substring(start, i);
                start = -1;
            } else if (!sep && start < 0) {
                start = i;
            }
        }
        return out;
    }
}
