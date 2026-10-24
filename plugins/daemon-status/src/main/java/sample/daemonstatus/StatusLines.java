package sample.daemonstatus;

import java.util.HashMap;
import java.util.Map;

/** Parser for the daemons' {@code key=value} status replies. */
final class StatusLines {

    private StatusLines() {
    }

    /**
     * Parses {@code key=value} lines (split at the first '='); lines without a key or an '=' are ignored. A reply
     * whose {@code v} is not {@code 1} is unknown and yields an empty map.
     */
    static Map<String, String> parse(String text) {
        Map<String, String> out = new HashMap<String, String>();
        if (text == null) {
            return out;
        }
        int pos = 0;
        while (pos < text.length()) {
            int end = text.indexOf('\n', pos);
            if (end < 0) {
                end = text.length();
            }
            String line = text.substring(pos, end);
            pos = end + 1;
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            int eq = line.indexOf('=');
            if (eq > 0) {
                out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        if (!"1".equals(out.get("v"))) {
            out.clear();
        }
        return out;
    }
}
