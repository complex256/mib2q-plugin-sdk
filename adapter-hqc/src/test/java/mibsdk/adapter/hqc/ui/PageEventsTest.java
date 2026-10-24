package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mibsdk.platform.PageStack;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiSnapshot;
import mibsdk.platform.UiTable;
import org.junit.jupiter.api.Test;

/**
 * Carried requirement 1 (the page-event ruling): one SDK page open at a time; every top change sends PAGE_CLOSED(old)
 * then PAGE_SHOWN(new) into the UiTable; leaving SDK pages sends PAGE_CLOSED; a LINK to a missing key never sends a
 * transient PAGE_SHOWN(missing).
 */
class PageEventsTest {

    private final UiTable table = new UiTable(32);
    private final PageStack stack = new PageStack(new PageEvents(table));

    private static UiSnapshot snap(String... keys) {
        Map<String, UiPage> m = new HashMap<String, UiPage>();
        for (String k : keys) {
            m.put(k, new UiPage(k, k, null));
        }
        return new UiSnapshot(1, null, "", m, 1, keys.length > 0 ? keys[0] : null);
    }

    private List<String> events() throws InterruptedException {
        List<String> l = new ArrayList<String>();
        for (UiInput in = table.poll(0); in != null; in = table.poll(0)) {
            String kind = in.kind() == UiInput.PAGE_SHOWN ? "shown " : in.kind() == UiInput.PAGE_CLOSED ? "closed "
                    : "kind" + in.kind() + " ";
            l.add(kind + in.pageKey());
        }
        return l;
    }

    @Test
    void everyTopChangeClosesTheOldThenShowsTheNew() throws Exception {
        UiSnapshot s = snap("a/home", "a/sub", "b/home");
        stack.open("a/home", "stock-screen");
        assertEquals(Arrays.asList("shown a/home"), events());

        stack.push("a/sub", null, s); // LINK to a present page
        assertEquals(Arrays.asList("closed a/home", "shown a/sub"), events());

        stack.push("a/missing", null, s); // LINK to a missing page: straight to the notice
        assertEquals(Arrays.asList("closed a/sub", "shown mibsdk/stopped"), events());

        stack.back();
        assertEquals(Arrays.asList("closed mibsdk/stopped", "shown a/sub"), events());

        stack.open("b/home", "stock-screen"); // a runtime open request replaces the stack
        assertEquals(Arrays.asList("closed a/sub", "shown b/home"), events());

        stack.reconcile(snap("a/home")); // b's page vanished (plugin stopped)
        assertEquals(Arrays.asList("closed b/home", "shown mibsdk/stopped"), events());

        assertEquals("stock-screen", stack.back(), "back from the bottom leaves SDK pages");
        assertEquals(Arrays.asList("closed mibsdk/stopped"), events());
    }
}
