package mibsdk.adapter.hqc;

import java.util.Hashtable;

/**
 * Stock DSI {@code int} constants read at run time. The jxe2jar jar we compile against holds them byte-swapped,
 * and javac would inline that wrong value; reading the field reflectively gets the value of the class actually
 * loaded. Results (failures too) are cached, so each lookup reflects and logs at most once.
 */
final class DsiConstants {

    private static final Integer FAILED = new Integer(-1);
    /** "class#field" to value. Hashtable: its monitor is held only for the lookup or the store. */
    private static final Hashtable CACHE = new Hashtable();

    private DsiConstants() {
    }

    /** The value of {@code className.field}, or -1 (logged once) if it cannot be read. Never throws. */
    static int get(String className, String field) {
        try {
            String key = className + '#' + field;
            Integer v = (Integer) CACHE.get(key);
            if (v == null) {
                v = read(className, field);
                CACHE.put(key, v);
            }
            return v.intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Integer read(String className, String field) {
        try {
            return new Integer(Class.forName(className).getField(field).getInt(null));
        } catch (Throwable t) {
            SdkGlobals.LOG.append("WARN", "mibsdk.hqc", "constant " + className + "." + field + " unreadable: " + t);
            return FAILED;
        }
    }
}
