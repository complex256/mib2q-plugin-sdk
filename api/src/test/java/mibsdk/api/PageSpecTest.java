package mibsdk.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PageSpecTest {

    private static final Page TARGET = new Page() {
        @Override
        public String id() {
            return "target";
        }

        @Override
        public void update(PageSpec spec) {
        }

        @Override
        public void open() {
        }

        @Override
        public void cancel() {
        }
    };

    @Test
    void builderIsImmutable() {
        PageSpec a = PageSpec.of("p", "T");
        PageSpec b = a.item("x", "X", null);
        assertEquals(0, a.entries().size());
        assertEquals(1, b.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> b.entries().clear());
    }

    @Test
    void rejectsBadAndDuplicateIds() {
        assertThrows(IllegalArgumentException.class, () -> PageSpec.of("bad id", "T"));
        assertThrows(IllegalArgumentException.class,
                () -> PageSpec.of("p", "T").item("x", "X", null).toggle("x", "Y", true));
        for (String bad : new String[] {null, "", "a/b", "é", "abcdefghijabcdefghijabcdefghijabc" /* 33 */}) {
            assertThrows(IllegalArgumentException.class, () -> PageSpec.of(bad, "T"), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class, () -> PageSpec.of("p", "T").item(bad, "X", null),
                    String.valueOf(bad));
        }
        assertEquals("aZ0._-abcdefghijabcdefghijabcdef", PageSpec.of("aZ0._-abcdefghijabcdefghijabcdef", "T").id());
        assertThrows(IllegalArgumentException.class, () -> PageSpec.of("p", "T").link("l", "L", null));
    }

    @Test
    void neverRejectsText() {
        String longText = "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890";
        PageSpec s = PageSpec.of("p", null).item("x", longText, longText).info(null).info(longText);
        assertNull(s.title());
        assertEquals(longText, s.entries().get(0).label());
        assertNull(s.entries().get(1).label());
    }

    @Test
    void capsEntryCountAt24() {
        PageSpec s = PageSpec.of("p", "T");
        for (int i = 0; i < PageSpec.MAX_ENTRIES; i++) {
            s = s.info("line " + i);
        }
        assertEquals(24, s.entries().size());
        final PageSpec full = s;
        assertThrows(IllegalStateException.class, () -> full.info("one too many"));
        assertThrows(IllegalStateException.class, () -> full.item("x", "X", null));
    }

    @Test
    void keepsEntryOrderAndKinds() {
        PageSpec s = PageSpec.of("p", "Title")
                .item("i", "Item", "val")
                .toggle("t", "Toggle", true)
                .info("Info text")
                .link("l", "Link", TARGET);
        assertEquals("p", s.id());
        assertEquals("Title", s.title());
        List<PageSpec.Entry> e = s.entries();
        assertEquals(4, e.size());

        assertEquals(PageSpec.Kind.ITEM, e.get(0).kind());
        assertEquals("i", e.get(0).itemId());
        assertEquals("Item", e.get(0).label());
        assertEquals("val", e.get(0).value());

        assertEquals(PageSpec.Kind.TOGGLE, e.get(1).kind());
        assertEquals("t", e.get(1).itemId());
        assertTrue(e.get(1).on());

        assertEquals(PageSpec.Kind.INFO, e.get(2).kind());
        assertNull(e.get(2).itemId());
        assertEquals("Info text", e.get(2).label());
        assertFalse(e.get(2).on());

        assertEquals(PageSpec.Kind.LINK, e.get(3).kind());
        assertEquals("l", e.get(3).itemId());
        assertSame(TARGET, e.get(3).target());
    }

    @Test
    void uiActionCarriesItsFields() {
        UiAction item = new UiAction("p", "i", null);
        assertEquals("p", item.pageId());
        assertEquals("i", item.itemId());
        assertNull(item.requestedOn());
        assertEquals(Boolean.TRUE, new UiAction("p", "t", Boolean.TRUE).requestedOn());
    }
}
