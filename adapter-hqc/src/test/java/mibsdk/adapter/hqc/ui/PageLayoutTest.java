package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiPage;
import org.junit.jupiter.api.Test;

class PageLayoutTest {

    private static final UiIcon A = UiIcon.stock("a");
    private static final UiIcon B = UiIcon.stock("b");
    private static final UiIcon C = UiIcon.stock("c"); // no id given

    private static UiEntry item(String key, UiIcon icon) {
        return new UiEntry(UiEntry.ITEM, key, key, null, false, null).withIcon(icon);
    }

    private static ItemIcons ids() {
        Map<UiIcon, Integer> m = new HashMap<UiIcon, Integer>();
        m.put(A, 609100);
        m.put(B, 609101);
        return new ItemIcons(m);
    }

    @Test
    void distinctIdsInOrderAndIndex() {
        UiPage p = new UiPage("p/main", "Main", Arrays.asList(item("1", B), item("2", A), item("3", B), item("4", C),
                item("5", null), new UiEntry(UiEntry.INFO, "i", "info", null, false, null)));
        PageLayout l = PageLayout.of(p, ids());
        assertArrayEquals(new int[] {609101, 609100}, l.iconIds(), "first appearance order, each id once");
        assertEquals(0, l.iconIndex(B));
        assertEquals(1, l.iconIndex(A));
        assertEquals(-1, l.iconIndex(C), "no id: not in the layout");
        assertEquals(-1, l.iconIndex(null));

        PageLayout same = PageLayout.of(new UiPage("p/main", "Main", Arrays.asList(item("x", B), item("y", A),
                item("z", A))), ids());
        assertEquals(l, same, "a row moving to an icon already in the set is the same layout");
        assertEquals(l.hashCode(), same.hashCode());
        assertNotEquals(l, PageLayout.of(new UiPage("p/main", "Main", Arrays.asList(item("x", A), item("y", B))),
                ids()), "another order: other indexes");
        assertEquals(PageLayout.EMPTY, PageLayout.of(new UiPage("p/main", "Main", Arrays.asList(item("x", C))), ids()));
    }
}
