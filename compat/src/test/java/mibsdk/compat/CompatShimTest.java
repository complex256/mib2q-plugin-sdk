package mibsdk.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import mibsdk.compat.java.lang._Class;
import mibsdk.compat.java.lang._Integer;
import mibsdk.compat.java.lang._Thread;
import mibsdk.compat.java.util._Arrays;
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

    @Test
    void arraysCopyOfKeepsComponentTypeAndPads() {
        Object[] copy = _Arrays.copyOf(new String[] {"a", "b"}, 3);
        assertEquals(String[].class, copy.getClass());
        assertArrayEquals(new String[] {"a", "b", null}, copy);
        assertArrayEquals(new int[] {1}, _Arrays.copyOf(new int[] {1, 2}, 1));
        assertEquals(Object[].class, _Arrays.copyOf(new String[] {"a"}, 1, Object[].class).getClass());
    }

    @Test
    void arraysCopyOfRangeChecksBoundsLikeTheJdk() {
        assertArrayEquals(new String[] {"b", null}, _Arrays.copyOfRange(new String[] {"a", "b"}, 1, 3));
        assertArrayEquals(new long[] {2L, 0L}, _Arrays.copyOfRange(new long[] {1L, 2L}, 1, 3));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> _Arrays.copyOfRange(new byte[1], 2, 3));
        assertThrows(IllegalArgumentException.class, () -> _Arrays.copyOfRange(new Object[2], 2, 1));
    }

    @Test
    void threadStackTraceOnlyForTheCurrentThread() {
        assertTrue(_Thread.getStackTrace(Thread.currentThread()).length > 0);
        assertEquals(0, _Thread.getStackTrace(new Thread()).length);
    }

    @Test
    void classGenericInterfacesAreTheRawInterfaces() {
        assertArrayEquals(new Object[] {Runnable.class}, _Class.getGenericInterfaces(Thread.class));
    }
}
