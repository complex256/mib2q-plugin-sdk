package mibsdk.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent JSON parser. Yields Map (LinkedHashMap), List (ArrayList), String, Long,
 * Boolean or null. Numbers must be integers. No regex, String.format or String.split: the car lacks them.
 */
public final class MiniJson {

    private static final int MAX_DEPTH = 64;

    private final String s;
    private int pos;

    private MiniJson(String s) {
        this.s = s;
    }

    public static Object parse(String json) throws ManifestException {
        if (json == null) {
            throw new ManifestException("json is null");
        }
        MiniJson p = new MiniJson(json);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != p.s.length()) {
            throw p.error("trailing content");
        }
        return v;
    }

    private ManifestException error(String what) {
        return new ManifestException("bad json at offset " + pos + ": " + what);
    }

    private void skipWs() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private Object value(int depth) throws ManifestException {
        if (depth > MAX_DEPTH) {
            throw error("nesting too deep");
        }
        if (pos >= s.length()) {
            throw error("unexpected end");
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("unexpected '" + c + "'");
        }
    }

    private void literal(String word) throws ManifestException {
        if (!s.startsWith(word, pos)) {
            throw error("expected " + word);
        }
        pos += word.length();
    }

    private Object number() throws ManifestException {
        int start = pos;
        if (s.charAt(pos) == '-') {
            pos++;
        }
        int digits = pos;
        while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
            pos++;
        }
        if (pos == digits) {
            throw error("expected digits");
        }
        if (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == '.' || c == 'e' || c == 'E') {
                throw error("only integer numbers are supported");
            }
        }
        try {
            return new Long(Long.parseLong(s.substring(start, pos)));
        } catch (NumberFormatException e) {
            throw error("integer out of range");
        }
    }

    private String string() throws ManifestException {
        pos++; // opening quote
        StringBuffer sb = new StringBuffer();
        while (true) {
            if (pos >= s.length()) {
                throw error("unterminated string");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw error("control character in string");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) {
                throw error("unterminated escape");
            }
            char e = s.charAt(pos++);
            switch (e) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    sb.append(hex4());
                    break;
                default:
                    throw error("bad escape '\\" + e + "'");
            }
        }
    }

    private char hex4() throws ManifestException {
        if (pos + 4 > s.length()) {
            throw error("short \\u escape");
        }
        int v = 0;
        for (int i = 0; i < 4; i++) {
            int d = Character.digit(s.charAt(pos + i), 16);
            if (d < 0) {
                throw error("bad \\u escape");
            }
            v = (v << 4) | d;
        }
        pos += 4;
        return (char) v;
    }

    private List<Object> array(int depth) throws ManifestException {
        pos++; // [
        List<Object> out = new ArrayList<Object>();
        skipWs();
        if (pos < s.length() && s.charAt(pos) == ']') {
            pos++;
            return out;
        }
        while (true) {
            skipWs();
            out.add(value(depth + 1));
            skipWs();
            if (pos >= s.length()) {
                throw error("unterminated array");
            }
            char c = s.charAt(pos++);
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw error("expected ',' or ']'");
            }
        }
    }

    private Map<String, Object> object(int depth) throws ManifestException {
        pos++; // {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        skipWs();
        if (pos < s.length() && s.charAt(pos) == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != '"') {
                throw error("expected string key");
            }
            String key = string();
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != ':') {
                throw error("expected ':'");
            }
            pos++;
            skipWs();
            out.put(key, value(depth + 1));
            skipWs();
            if (pos >= s.length()) {
                throw error("unterminated object");
            }
            char c = s.charAt(pos++);
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw error("expected ',' or '}'");
            }
        }
    }
}
