package mibsdk.adapter.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiRow;
import mibsdk.platform.UiSnapshot;
import mibsdk.platform.UiTable;
import org.junit.jupiter.api.Test;

class FakeUiTest {

    private final UiTable table = new UiTable(8);
    private final FakeUi ui = new FakeUi(table);

    private void publish(long version, long openSeq, String open) {
        Map<String, UiPage> pages = new HashMap<String, UiPage>();
        pages.put("mibsdk/plugins", new UiPage("mibsdk/plugins", "Plugins", Arrays.asList(
                new UiEntry(UiEntry.LINK, "sdk", "SDK", null, false, "sdk/main"),
                new UiEntry(UiEntry.INFO, "i", "All good", null, false, null))));
        pages.put("sdk/main", new UiPage("sdk/main", "SDK", Arrays.asList(
                new UiEntry(UiEntry.ITEM, "apple", "Apple", "3", false, null),
                new UiEntry(UiEntry.TOGGLE, "rack", "Rack", null, true, null))));
        table.publish(new UiSnapshot(version, Arrays.asList(
                new UiRow("mibsdk/plugins", "Plugins", "mibsdk/plugins")), "SDK ok", pages, openSeq, open));
    }

    @Test
    void rendersEntriesInOrder() {
        publish(1, 0, null);
        assertEquals(Arrays.asList("Plugins"), ui.carRows());
        assertEquals("SDK ok", ui.statusLine());
        assertEquals(Arrays.asList("SDK >", "All good"), ui.render("mibsdk/plugins"));
        assertEquals(Arrays.asList("Apple: 3", "[on] Rack"), ui.render("sdk/main"));
        assertTrue(ui.render("nope").isEmpty());
    }

    @Test
    void linkNavigatesWithoutInput() throws Exception {
        publish(1, 1, "mibsdk/plugins");
        assertEquals("mibsdk/plugins", ui.openPageKey());
        ui.select("mibsdk/plugins", "sdk");
        assertEquals("sdk/main", ui.openPageKey());
        for (String e : drain()) {
            assertFalse(e.startsWith("E:"), e);
        }
    }

    private java.util.List<String> drain() throws Exception {
        java.util.List<String> out = new java.util.ArrayList<String>();
        UiInput in;
        while ((in = table.poll(0)) != null) {
            String[] k = {"R", "E", "T", "+", "-"};
            out.add(k[in.kind()] + ":" + in.pageKey());
        }
        return out;
    }

    @Test
    void linkThenBackPairsPageEvents() throws Exception {
        publish(1, 1, "mibsdk/plugins");
        ui.select("mibsdk/plugins", "sdk");
        ui.back();
        ui.back();
        assertEquals(Arrays.asList("+:mibsdk/plugins", "-:mibsdk/plugins", "+:sdk/main", "-:sdk/main",
                "+:mibsdk/plugins", "-:mibsdk/plugins"), drain());
    }

    @Test
    void linkToLinkClosesEachPrevious() throws Exception {
        publish(1, 1, "mibsdk/plugins");
        ui.select("mibsdk/plugins", "sdk");
        Map<String, UiPage> pages = new HashMap<String, UiPage>(table.snapshot().pages());
        pages.put("sdk/main", new UiPage("sdk/main", "SDK", Arrays.asList(
                new UiEntry(UiEntry.LINK, "deep", "Deep", null, false, "sdk/deep"))));
        pages.put("sdk/deep", new UiPage("sdk/deep", "Deep", null));
        table.publish(new UiSnapshot(2, null, "", pages, 1, "mibsdk/plugins"));
        ui.select("sdk/main", "deep");
        assertEquals("sdk/deep", ui.openPageKey());
        assertEquals(Arrays.asList("+:mibsdk/plugins", "-:mibsdk/plugins", "+:sdk/main", "-:sdk/main", "+:sdk/deep"),
                drain());
    }

    @Test
    void newOpenSeqReplacesOpenPage() throws Exception {
        publish(1, 1, "mibsdk/plugins");
        ui.select("mibsdk/plugins", "sdk");
        drain();
        publish(2, 2, "mibsdk/plugins");
        assertEquals("mibsdk/plugins", ui.openPageKey());
        assertEquals(Arrays.asList("-:sdk/main", "+:mibsdk/plugins"), drain());
    }

    @Test
    void vanishedPageBecomesStoppedNotice() throws Exception {
        publish(1, 1, "sdk/main");
        assertEquals("sdk/main", ui.openPageKey());
        drain();
        table.publish(new UiSnapshot(2, null, "", new HashMap<String, UiPage>(), 1, "sdk/main"));
        assertEquals("mibsdk/stopped", ui.openPageKey());
        assertEquals(Arrays.asList("-:sdk/main", "+:mibsdk/stopped"), drain());
    }

    @Test
    void linkToMissingTargetBecomesStoppedNotice() throws Exception {
        publish(1, 1, "mibsdk/plugins");
        Map<String, UiPage> pages = new HashMap<String, UiPage>();
        pages.put("mibsdk/plugins", new UiPage("mibsdk/plugins", "Plugins", Arrays.asList(
                new UiEntry(UiEntry.LINK, "gone", "Gone", null, false, "x/missing"))));
        table.publish(new UiSnapshot(2, null, "", pages, 1, "mibsdk/plugins"));
        ui.select("mibsdk/plugins", "gone");
        assertEquals("mibsdk/stopped", ui.openPageKey());
    }

    @Test
    void toggleSendsRequestedValue() throws Exception {
        publish(1, 0, null);
        ui.toggle("sdk/main", "rack");
        UiInput in = table.poll(0);
        assertEquals(UiInput.TOGGLE_REQUESTED, in.kind());
        assertEquals("sdk/main", in.pageKey());
        assertEquals("rack", in.entryKey());
        assertFalse(in.on());
        ui.select("sdk/main", "apple");
        in = table.poll(0);
        assertEquals(UiInput.ENTRY_SELECTED, in.kind());
        assertEquals("apple", in.entryKey());
    }

    @Test
    void selectRowOpensPage() throws Exception {
        publish(1, 0, null);
        ui.selectRow(0);
        UiInput in = table.poll(0);
        assertEquals(UiInput.ROW_SELECTED, in.kind());
        assertEquals("mibsdk/plugins", in.pageKey());
        assertNull(ui.openPageKey());
        publish(2, 1, "mibsdk/plugins");
        assertEquals("mibsdk/plugins", ui.openPageKey());
    }

    @Test
    void backPopsToPrevious() {
        publish(1, 1, "mibsdk/plugins");
        ui.select("mibsdk/plugins", "sdk");
        assertEquals("sdk/main", ui.openPageKey());
        ui.back();
        assertEquals("mibsdk/plugins", ui.openPageKey());
        ui.back();
        assertNull(ui.openPageKey());
    }
}
