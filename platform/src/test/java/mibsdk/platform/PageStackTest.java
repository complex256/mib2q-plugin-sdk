package mibsdk.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PageStackTest {

    private static UiSnapshot snapWith(String... keys) {
        Map<String, UiPage> pages = new HashMap<String, UiPage>();
        for (String k : keys) {
            pages.put(k, new UiPage(k, k, null));
        }
        return new UiSnapshot(1, null, "", pages, 0, null);
    }

    @Test
    void pushBackReturnsOrigin() {
        PageStack s = new PageStack();
        Object origin = new Object();
        assertNull(s.top());
        s.push("a/1", origin);
        s.push("a/2", null);
        assertEquals("a/2", s.top());
        assertNull(s.back());
        assertEquals("a/1", s.top());
        assertSame(origin, s.back());
        assertNull(s.top());
        assertNull(s.back());
    }

    @Test
    void vanishedPageIsReplacedAndBackStillPops() {
        PageStack s = new PageStack();
        Object origin = new Object();
        s.push("a/1", origin);
        s.push("a/2", null);
        s.reconcile(snapWith("a/1"));
        assertEquals("mibsdk/stopped", s.top());
        s.reconcile(snapWith("a/1", "a/2")); // a returning page does not resurrect the notice
        assertEquals("mibsdk/stopped", s.top());
        assertNull(s.back());
        assertEquals("a/1", s.top());
        s.reconcile(snapWith("a/1"));
        assertEquals("a/1", s.top());
        assertSame(origin, s.back());
    }

    @Test
    void noticeUnderSameKeyKeepsPage() {
        PageStack s = new PageStack();
        s.push("a/1", null);
        s.reconcile(snapWith("a/1")); // hub keeps the key and swaps entries for a notice
        assertEquals("a/1", s.top());
    }

    @Test
    void linkCycleDepthCappedAt8() {
        PageStack s = new PageStack();
        Object origin = new Object();
        s.push("p0", origin);
        for (int i = 1; i <= 8; i++) {
            s.push("p" + i, null);
        }
        assertEquals("p8", s.top());
        for (int i = 8; i > 2; i--) {
            assertNull(s.back());
        }
        assertEquals("p2", s.top()); // p1 was dropped
        assertNull(s.back());
        assertEquals("p0", s.top());
        assertSame(origin, s.back());
    }
}
