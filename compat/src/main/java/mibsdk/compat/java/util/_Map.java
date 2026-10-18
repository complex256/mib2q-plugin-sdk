package mibsdk.compat.java.util;
import java.util.Map;
import mibsdk.compat.java.util.function.BiFunction;
public final class _Map {
    private _Map() {}
    public static Object merge(Map m, Object k, Object v, BiFunction f) {
        Object old = m.get(k);
        Object nv = old == null ? v : f.apply(old, v);
        if (nv == null) m.remove(k); else m.put(k, nv);
        return nv;
    }
    public static Object getOrDefault(Map m, Object k, Object d) { Object v = m.get(k); return v != null || m.containsKey(k) ? v : d; }
}
