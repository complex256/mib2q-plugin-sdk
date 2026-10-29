package mibsdk.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class UiColorTest {

    @Test
    void packsArgb() {
        assertEquals(0xFFFF8000, UiColor.rgb(255, 128, 0).argb());
        assertEquals(0x80FF8000, UiColor.rgba(255, 128, 0, 128).argb());
        assertEquals(0xFF000000, UiColor.of(0xFF000000).argb());
    }

    @Test
    void valueEquality() {
        assertEquals(UiColor.rgb(1, 2, 3), UiColor.rgba(1, 2, 3, 255));
        assertEquals(UiColor.rgb(1, 2, 3).hashCode(), UiColor.of(0xFF010203).hashCode());
        assertNotEquals(UiColor.rgb(1, 2, 3), UiColor.rgba(1, 2, 3, 0));
        assertNotEquals(UiColor.rgb(1, 2, 3), null);
    }

    @Test
    void rejectsComponentsOutsideZeroTo255() {
        assertThrows(IllegalArgumentException.class, () -> UiColor.rgb(256, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> UiColor.rgb(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> UiColor.rgba(0, 0, 0, 256));
    }

    @Test
    void toStringIsThePackedHex() {
        assertEquals("UiColor{#ffff8000}", UiColor.rgb(255, 128, 0).toString());
        assertEquals("UiColor{#00000000}", UiColor.of(0).toString());
    }
}
