package mibsdk.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict TOML reader/writer for the flat config files the SDK owns (currently {@code wifi.toml}).
 *
 * <p>The car's class library is Java 1.4, so the usual Java TOML libraries (which need Java 7/8 APIs and extra
 * jars) cannot be linked. This covers the TOML the config uses: comments, bare and quoted keys, basic and literal
 * strings, booleans and integers. Tables, arrays, dates and dotted keys are rejected with a line number instead of
 * being silently misread. Duplicate keys are an error. The reader returns a typed {@link Table}; the writer is
 * {@link Writer}.
 */
final class Toml {

    private Toml() {
    }

    /** A parsed flat table. All getters validate the value's type and name the key in the error. */
    static final class Table {

        private final Map values;

        Table(Map values) {
            this.values = values;
        }

        boolean has(String key) {
            return values.containsKey(key);
        }

        String string(String key, String dflt) {
            Object v = values.get(key);
            if (v == null) {
                return dflt;
            }
            if (!(v instanceof String)) {
                throw new TomlException("'" + key + "' must be a string");
            }
            return (String) v;
        }

        boolean bool(String key, boolean dflt) {
            Object v = values.get(key);
            if (v == null) {
                return dflt;
            }
            if (!(v instanceof Boolean)) {
                throw new TomlException("'" + key + "' must be true or false");
            }
            return ((Boolean) v).booleanValue();
        }

        long integer(String key, long dflt) {
            Object v = values.get(key);
            if (v == null) {
                return dflt;
            }
            if (!(v instanceof Long)) {
                throw new TomlException("'" + key + "' must be an integer");
            }
            return ((Long) v).longValue();
        }

        /** Rejects any key not in {@code known}, so a typo fails loudly rather than being ignored. */
        void rejectUnknown(String[] known) {
            List keys = new ArrayList(values.keySet());
            Collections.sort(keys);
            for (int i = 0; i < keys.size(); i++) {
                String k = (String) keys.get(i);
                boolean ok = false;
                for (int j = 0; j < known.length; j++) {
                    if (known[j].equals(k)) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) {
                    throw new TomlException("unknown key '" + k + "'");
                }
            }
        }

        List keys() {
            return Collections.unmodifiableList(new ArrayList(values.keySet()));
        }
    }

    /** A TOML syntax or type error; the message is shown in the SDK log. */
    static final class TomlException extends RuntimeException {
        TomlException(String message) {
            super(message);
        }
    }

    /** Parses {@code text}; every error names the 1-based line. */
    static Table parse(String text) {
        Map values = new LinkedHashMap();
        int start = 0;
        int lineNo = 0;
        while (start <= text.length()) {
            int nl = text.indexOf('\n', start);
            String line = nl < 0 ? text.substring(start) : text.substring(start, nl);
            lineNo++;
            String t = stripComment(line).trim();
            if (t.length() > 0) {
                if (t.charAt(0) == '[') {
                    throw new TomlException("line " + lineNo + ": tables are not supported in this file");
                }
                int eq = indexOfUnquoted(t, '=');
                if (eq < 0) {
                    throw new TomlException("line " + lineNo + ": expected key = value");
                }
                String key = parseKey(t.substring(0, eq).trim(), lineNo);
                String raw = t.substring(eq + 1).trim();
                if (raw.length() == 0) {
                    throw new TomlException("line " + lineNo + ": missing value for '" + key + "'");
                }
                if (values.containsKey(key)) {
                    throw new TomlException("line " + lineNo + ": duplicate key '" + key + "'");
                }
                values.put(key, parseValue(raw, lineNo));
            }
            if (nl < 0) {
                break;
            }
            start = nl + 1;
        }
        return new Table(values);
    }

    /** Writes a flat table in a stable order, with an optional leading comment. */
    static final class Writer {
        private final StringBuffer out = new StringBuffer();

        Writer comment(String text) {
            out.append("# ").append(text).append('\n');
            return this;
        }

        Writer bool(String key, boolean value) {
            out.append(key).append(" = ").append(value).append('\n');
            return this;
        }

        Writer integer(String key, long value) {
            out.append(key).append(" = ").append(value).append('\n');
            return this;
        }

        Writer string(String key, String value) {
            out.append(key).append(" = \"").append(escape(value)).append('"').append('\n');
            return this;
        }

        public String toString() {
            return out.toString();
        }
    }

    // ---- internals ----

    private static Object parseValue(String raw, int line) {
        if (raw.length() == 0) {
            throw new TomlException("line " + line + ": empty value");
        }
        char c = raw.charAt(0);
        if (c == '"') {
            int end = closingQuote(raw, 0, '"');
            requireNoTrailing(raw, end, line);
            return unescape(raw.substring(1, end), line);
        }
        if (c == '\'') {
            int end = raw.indexOf('\'', 1);
            if (end < 0) {
                throw new TomlException("line " + line + ": unterminated literal string");
            }
            requireNoTrailing(raw, end, line);
            return raw.substring(1, end);
        }
        if ("true".equals(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equals(raw)) {
            return Boolean.FALSE;
        }
        if (isInteger(raw)) {
            try {
                return new Long(Long.parseLong(raw.replace("_", "")));
            } catch (NumberFormatException e) {
                throw new TomlException("line " + line + ": integer is out of range");
            }
        }
        throw new TomlException("line " + line + ": unsupported value '" + raw + "'");
    }

    private static boolean isInteger(String s) {
        int i = 0;
        if (s.length() > 0 && (s.charAt(0) == '+' || s.charAt(0) == '-')) {
            i = 1;
        }
        boolean digit = false;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_') {
                continue;
            }
            if (c < '0' || c > '9') {
                return false;
            }
            digit = true;
        }
        return digit;
    }

    private static int closingQuote(String raw, int from, char quote) {
        boolean escaped = false;
        for (int i = from + 1; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote == '"' && escaped) {
                escaped = false;
            } else if (quote == '"' && c == '\\') {
                escaped = true;
            } else if (c == quote) {
                return i;
            }
        }
        throw new TomlException("unterminated string");
    }

    private static void requireNoTrailing(String raw, int end, int line) {
        String rest = raw.substring(end + 1).trim();
        if (rest.length() > 0) {
            throw new TomlException("line " + line + ": unexpected text after value: '" + rest + "'");
        }
    }

    private static String unescape(String s, int line) {
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (++i >= s.length()) {
                throw new TomlException("line " + line + ": trailing backslash in string");
            }
            char n = s.charAt(i);
            if (n == 'n') {
                b.append('\n');
            } else if (n == 'r') {
                b.append('\r');
            } else if (n == 't') {
                b.append('\t');
            } else if (n == 'b') {
                b.append('\b');
            } else if (n == 'f') {
                b.append('\f');
            } else if (n == '"' || n == '\\') {
                b.append(n);
            } else if (n == 'u') {
                if (i + 4 >= s.length()) {
                    throw new TomlException("line " + line + ": short \\u escape");
                }
                try {
                    b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                } catch (NumberFormatException e) {
                    throw new TomlException("line " + line + ": bad \\u escape");
                }
                i += 4;
            } else {
                throw new TomlException("line " + line + ": unsupported escape \\" + n);
            }
        }
        return b.toString();
    }

    private static String parseKey(String key, int line) {
        if (key.length() == 0) {
            throw new TomlException("line " + line + ": empty key");
        }
        if (key.charAt(0) == '"') {
            int end = closingQuote(key, 0, '"');
            requireNoTrailing(key, end, line);
            return unescape(key.substring(1, end), line);
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'
                    || c == '-';
            if (!ok) {
                throw new TomlException("line " + line + ": unsupported key '" + key + "' (bare keys only)");
            }
        }
        return key;
    }

    /** Cuts a {@code #} comment that is outside a string. */
    private static String stripComment(String line) {
        boolean basic = false;
        boolean literal = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (basic) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    basic = false;
                }
            } else if (literal) {
                if (c == '\'') {
                    literal = false;
                }
            } else if (c == '"') {
                basic = true;
            } else if (c == '\'') {
                literal = true;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int indexOfUnquoted(String line, char want) {
        boolean basic = false;
        boolean literal = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (basic) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    basic = false;
                }
            } else if (literal) {
                if (c == '\'') {
                    literal = false;
                }
            } else if (c == '"') {
                basic = true;
            } else if (c == '\'') {
                literal = true;
            } else if (c == want) {
                return i;
            }
        }
        return -1;
    }

    private static String escape(String s) {
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c == '\n') {
                b.append("\\n");
            } else if (c == '\r') {
                b.append("\\r");
            } else if (c == '\t') {
                b.append("\\t");
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
