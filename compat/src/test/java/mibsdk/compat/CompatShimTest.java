package mibsdk.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import mibsdk.compat.java.lang._Integer;
import mibsdk.compat.java.util.Objects;
import mibsdk.compat.java.util._Locale;
import mibsdk.compat.java.util._Map;
import org.junit.jupiter.api.Test;

/** Runs on the host JVM; the shims themselves are compiled as Java 1.4 / class version 48. */
class CompatShimTest {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void mergeCombinesExistingValue() {
        Map m = new HashMap();
        m.put("a", 5L);
        Object r = _Map.merge(m, "a", 2L, (a, b) -> (Long) a + (Long) b);
        assertEquals(7L, r);
        assertEquals(7L, m.get("a"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void mergeInsertsWhenAbsent() {
        Map m = new HashMap();
        assertEquals(2L, _Map.merge(m, "a", 2L, (a, b) -> (Long) a + (Long) b));
        assertEquals(2L, m.get("a"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void mergeWithNullResultRemovesKey() {
        Map m = new HashMap();
        m.put("a", 5L);
        assertNull(_Map.merge(m, "a", 2L, (a, b) -> null));
        assertFalse(m.containsKey("a"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void getOrDefaultReturnsDefaultOnlyWhenKeyAbsent() {
        Map m = new HashMap();
        assertEquals("d", _Map.getOrDefault(m, "zz", "d"));
        m.put("n", null);
        assertNull(_Map.getOrDefault(m, "n", "d"));
    }

    @Test
    void objectsEqualsIsNullSafe() {
        assertTrue(Objects.equals(null, null));
        assertFalse(Objects.equals(null, "x"));
        assertTrue(Objects.equals("x", "x"));
    }

    @Test
    void integerHashCodeIsTheValue() {
        assertEquals(42, _Integer.hashCode(42));
    }

    @Test
    void localeRootHasEmptyLanguage() {
        assertEquals("", _Locale.ROOT.getLanguage());
    }
}
