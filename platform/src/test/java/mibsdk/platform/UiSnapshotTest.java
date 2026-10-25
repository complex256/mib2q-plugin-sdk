package mibsdk.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class UiSnapshotTest {

    @Test
    void oldConstructorMakesOneTextPart() {
        UiSnapshot s = new UiSnapshot(1, null, "SAFE A up", null, 0, null);
        assertEquals("SAFE A up", s.statusLine());
        assertEquals(1, s.statusParts().size());
        UiStatusPart p = s.statusParts().get(0);
        assertEquals("SAFE A up", p.text());
        assertNull(p.icon());
        assertFalse(p.indicator());
        assertTrue(new UiSnapshot(1, null, "", null, 0, null).statusParts().isEmpty(), "no part for an empty line");
        assertTrue(new UiSnapshot(1, null, null, null, 0, null).statusParts().isEmpty());
        assertTrue(UiSnapshot.EMPTY.statusParts().isEmpty());
        assertTrue(UiSnapshot.down(2).statusParts().isEmpty());
    }

    @Test
    void newConstructorKeepsTheLineAndThePartsApart() {
        UiIcon star = UiIcon.stock("star");
        List<UiStatusPart> parts = new ArrayList<UiStatusPart>(Arrays.asList(
                new UiStatusPart("SAFE", null, true), new UiStatusPart("ABC", star, false)));
        UiSnapshot s = new UiSnapshot(1, null, "SAFE ABCD", null, 0, null, parts);
        assertEquals("SAFE ABCD", s.statusLine(), "the text-only line is its own");
        assertEquals(2, s.statusParts().size());
        assertSame(star, s.statusParts().get(1).icon());
        parts.clear();
        assertEquals(2, s.statusParts().size(), "a copy");
        assertThrows(UnsupportedOperationException.class, () -> s.statusParts().clear());
        assertTrue(new UiSnapshot(1, null, "", null, 0, null, null).statusParts().isEmpty());
    }

    @Test
    void rowIconIsOptional() {
        assertNull(new UiRow("k", "L", "p").icon());
        UiIcon star = UiIcon.stock("star");
        assertSame(star, new UiRow("k", "L", "p", star).icon());
    }
}
