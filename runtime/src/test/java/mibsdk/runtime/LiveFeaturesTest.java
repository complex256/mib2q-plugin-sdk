package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LiveFeaturesTest {

    private static Set<String> set(String... s) {
        return new HashSet<String>(Arrays.asList(s));
    }

    @Test
    void setReportsOnlyAChange() {
        Set<String> initial = set("a");
        LiveFeatures f = new LiveFeatures(initial);
        initial.add("b");
        assertFalse(f.has("b"), "a copy is kept");
        assertTrue(f.has("a"));
        assertFalse(f.set(set("a")), "same set");
        assertTrue(f.set(set("a", "b")));
        assertTrue(f.has("b"));
        assertEquals(set("a", "b"), f.snapshot());
        assertTrue(f.set(set()));
        assertFalse(f.has("a"));
        assertFalse(f.has(null));
    }

    @Test
    void snapshotIsUnmodifiable() {
        LiveFeatures f = new LiveFeatures(set("a"));
        assertThrows(UnsupportedOperationException.class, () -> f.snapshot().add("x"));
    }
}
