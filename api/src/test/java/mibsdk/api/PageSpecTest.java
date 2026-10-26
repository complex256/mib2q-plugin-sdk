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
        assertNull(item.value());
        assertNull(item.text());
        UiAction picked = new UiAction("p", "c", null, Integer.valueOf(2), "abc");
        assertEquals(Integer.valueOf(2), picked.value());
        assertEquals("abc", picked.text());
        assertTrue(picked.toString().contains("value=2"), picked.toString());
        assertTrue(picked.toString().contains("text=abc"), picked.toString());
    }

    // ---- api 5 ----

    private static final String[] THREE = {"Low", "Mid", "High"};

    @Test
    void choiceAndDropdownRejectBadOptions() {
        final PageSpec p = PageSpec.of("p", "T");
        final String[] nine = {"1", "2", "3", "4", "5", "6", "7", "8", "9"};
        final String[] withNull = {"a", null};
        for (int kind = 0; kind < 2; kind++) {
            final boolean dd = kind == 1;
            assertThrows(IllegalArgumentException.class, () -> build(p, dd, null, 0), "null options");
            assertThrows(IllegalArgumentException.class, () -> build(p, dd, new String[0], 0), "0 options");
            assertThrows(IllegalArgumentException.class, () -> build(p, dd, nine, 0), "9 options");
            assertThrows(IllegalArgumentException.class, () -> build(p, dd, withNull, 0), "a null option");
            assertThrows(IllegalArgumentException.class, () -> build(p, dd, THREE, -1), "selected -1");
            assertThrows(IllegalArgumentException.class, () -> build(p, dd, THREE, 3), "selected n");
            assertEquals(2, build(p, dd, THREE, 2).entries().get(0).number());
            assertEquals(PageSpec.MAX_OPTIONS,
                    build(p, dd, new String[] {"1", "2", "3", "4", "5", "6", "7", "8"}, 7).entries().get(0)
                            .options().length);
        }
    }

    private static PageSpec build(PageSpec p, boolean dropdown, String[] options, int selected) {
        return dropdown ? p.dropdown("c", "C", options, selected) : p.choice("c", "C", options, selected);
    }

    @Test
    void sliderRules() {
        final PageSpec p = PageSpec.of("p", "T");
        assertThrows(IllegalArgumentException.class, () -> p.slider("s", "S", 0, 10, 3, 0, false), "10 % 3");
        assertThrows(IllegalArgumentException.class, () -> p.slider("s", "S", 0, 30, 1, 0, false), "30 steps");
        assertThrows(IllegalArgumentException.class, () -> p.slider("s", "S", 0, 10, 2, 5, false), "off step");
        assertThrows(IllegalArgumentException.class, () -> p.slider("s", "S", 0, 9, 1, 0, true), "odd centred");
        assertThrows(IllegalArgumentException.class, () -> p.slider("s", "S", 5, 5, 1, 5, false), "min == max");
        assertThrows(IllegalArgumentException.class, () -> p.slider("s", "S", 0, 10, 0, 0, false), "step 0");
        assertThrows(IllegalArgumentException.class, () -> p.slider("s", "S", 0, 10, 2, 12, false), "above max");
        assertThrows(IllegalArgumentException.class, () -> p.slider("s", "S", 0, 10, 2, -2, false), "below min");
        assertThrows(IllegalArgumentException.class,
                () -> p.slider("s", "S", Integer.MIN_VALUE, Integer.MAX_VALUE, 1, 0, false), "no overflow");

        PageSpec.Entry c = p.slider("bass", "Bass", -6, 6, 1, 0, true).entries().get(0);
        assertEquals(PageSpec.Kind.SLIDER, c.kind());
        assertEquals(-6, c.min());
        assertEquals(6, c.max());
        assertEquals(1, c.step());
        assertEquals(0, c.number());
        assertTrue(c.centred());
        PageSpec.Entry s = p.slider("s", "S", 0, 10, 2, 4, false).entries().get(0);
        assertEquals(4, s.number());
        assertEquals(2, s.step());
        assertFalse(s.centred());
        assertEquals(24, PageSpec.MAX_SLIDER_STEPS);
        p.slider("s", "S", 0, 24, 1, 24, true); // exactly 24 steps
    }

    @Test
    void progressClamps() {
        PageSpec p = PageSpec.of("p", "T");
        assertEquals(0, p.progress("x", "X", -5).entries().get(0).number());
        assertEquals(100, p.progress("x", "X", 150).entries().get(0).number());
        assertEquals(60, p.progress("x", "X", 60).entries().get(0).number());
        assertEquals(PageSpec.Kind.PROGRESS, p.progress("x", "X", 60).entries().get(0).kind());
    }

    @Test
    void fifthTypedRowThrows() {
        PageSpec p = PageSpec.of("p", "T")
                .dropdown("d1", "D", THREE, 0)
                .slider("s1", "S", 0, 10, 1, 0, false)
                .choice("c1", "C", THREE, 0) // a list row: not counted
                .progress("g1", "G", 5) // nor this
                .dropdown("d2", "D", THREE, 0)
                .slider("s2", "S", 0, 10, 1, 0, false);
        assertEquals(PageSpec.MAX_INPUT_ROWS, 4);
        final PageSpec full = p;
        assertThrows(IllegalStateException.class, () -> full.dropdown("d3", "D", THREE, 0));
        assertThrows(IllegalStateException.class, () -> full.slider("s3", "S", 0, 10, 1, 0, false));
        full.choice("c2", "C", THREE, 1);
    }

    @Test
    void enabledOnlyOnListRows() {
        final PageSpec p = PageSpec.of("p", "T")
                .dropdown("lvl", "Level", THREE, 0)
                .toggle("t", "T", true)
                .item("i", "I", null)
                .link("l", "L", TARGET)
                .choice("c", "C", THREE, 0)
                .slider("s", "S", 0, 10, 1, 0, false)
                .progress("g", "G", 1)
                .info("text");
        assertThrows(IllegalArgumentException.class, () -> p.enabled("lvl", false));
        assertThrows(IllegalArgumentException.class, () -> p.enabled("s", false));
        assertThrows(IllegalArgumentException.class, () -> p.enabled("g", false));
        assertThrows(IllegalArgumentException.class, () -> p.enabled("nope", false));
        assertThrows(IllegalArgumentException.class, () -> p.enabled(null, false));
        for (PageSpec.Entry e : p.entries()) {
            assertTrue(e.enabled(), "enabled by default");
        }
        PageSpec off = p.enabled("t", false).enabled("i", false).enabled("l", false).enabled("c", false);
        assertFalse(off.entries().get(1).enabled());
        assertTrue(off.entries().get(1).on(), "the rest of the entry is kept");
        assertFalse(off.entries().get(2).enabled());
        assertFalse(off.entries().get(3).enabled());
        assertSame(TARGET, off.entries().get(3).target());
        assertFalse(off.entries().get(4).enabled());
        assertTrue(p.entries().get(1).enabled(), "immutable");
        assertTrue(off.enabled("t", true).entries().get(1).enabled());
        assertEquals(p.entries().size(), off.entries().size());
    }

    @Test
    void entriesExposeTheirFields() {
        String[] opts = {"English", "Deutsch"};
        PageSpec p = PageSpec.of("p", "T").dropdown("lang", "Language", opts, 1);
        opts[0] = "changed";
        PageSpec.Entry e = p.entries().get(0);
        assertEquals("English", e.options()[0], "the builder copies its array");
        e.options()[0] = "mutated";
        assertEquals("English", p.entries().get(0).options()[0], "options() is a copy");
        assertEquals(PageSpec.Kind.DROPDOWN, e.kind());
        assertEquals("Language", e.label());
        assertEquals(1, e.number());
        assertNull(PageSpec.of("p", "T").item("i", "I", null).entries().get(0).options());
        assertNull(PageSpec.of("p", "T").item("i", "I", null).entries().get(0).icon());

        Icon icon = new Icon() {
        };
        PageSpec.Entry withIcon = PageSpec.of("p", "T").item("i", "I", "v", icon).entries().get(0);
        assertSame(icon, withIcon.icon());
        assertEquals(PageSpec.Kind.ITEM, withIcon.kind());
        assertEquals("v", withIcon.value());
        assertNull(PageSpec.of("p", "T").item("i", "I", "v", null).entries().get(0).icon());
        assertEquals(24, PageSpec.ITEM_ICON_MAX_PX);
    }
}
