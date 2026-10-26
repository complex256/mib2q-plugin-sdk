package mibsdk.platform;

/**
 * Makes plugin text safe for the HMI's Latin-1 widgets: control chars become spaces, whitespace runs
 * collapse and the ends are trimmed, format/bidi marks are dropped, anything above U+00FF (a surrogate
 * pair counts once) becomes {@code ?}, and text longer than {@code max} is cut to {@code max - 3} chars
 * plus {@code ...} (a hard cut when {@code max <= 3}). {@link #cut} cleans the same way but always cuts hard, for an
 * editable value (a speller's text), where a "..." would come back as typed text.
 */
public final class UiText {

    private UiText() {
    }

    public static String clean(String s, int max) {
        return clean(s, max, true);
    }

    /** As {@link #clean}, but text longer than {@code max} is cut to {@code max} chars (trailing space trimmed). */
    public static String cut(String s, int max) {
        return clean(s, max, false);
    }

    private static String clean(String s, int max, boolean ellipsis) {
        if (s == null || max <= 0) {
            return "";
        }
        int n = s.length();
        StringBuffer out = new StringBuffer(n < 64 ? n : 64);
        boolean pendingSpace = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            char mapped;
            if (c < 0x20 || (c >= 0x7F && c <= 0x9F) || c == ' ') {
                pendingSpace = out.length() > 0;
                continue;
            } else if (isDropped(c)) {
                continue;
            } else if (c >= 0xD800 && c <= 0xDBFF) {
                if (i + 1 < n) {
                    char d = s.charAt(i + 1);
                    if (d >= 0xDC00 && d <= 0xDFFF) {
                        i++;
                    }
                }
                mapped = '?';
            } else if (c > 0xFF) {
                mapped = '?'; // includes a lone low surrogate
            } else {
                mapped = c;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(mapped);
            if (out.length() > max) {
                break; // already too long; the rest cannot change the result
            }
        }
        if (out.length() <= max) {
            return out.toString();
        }
        if (!ellipsis) {
            int end = max;
            while (end > 0 && out.charAt(end - 1) == ' ') {
                end--; // a cut right after a space
            }
            return out.substring(0, end);
        }
        if (max <= 3) {
            return out.substring(0, max);
        }
        out.setLength(max - 3);
        out.append("...");
        return out.toString();
    }

    private static boolean isDropped(char c) {
        return (c >= 0x200B && c <= 0x200F)
                || (c >= 0x202A && c <= 0x202E)
                || (c >= 0x2060 && c <= 0x2064)
                || c == 0xFEFF;
    }
}
