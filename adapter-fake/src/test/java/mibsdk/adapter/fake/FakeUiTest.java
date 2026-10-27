package mibsdk.adapter.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiRow;
import mibsdk.platform.UiSnapshot;
import mibsdk.platform.UiStatusPart;
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
            String[] k = {"R", "E", "T", "+", "-", "V", "D"};
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
        assertEquals("mibsdk/plugins", ui.openPageKey());
        drain();
        ui.select("mibsdk/plugins", "gone");
        assertEquals("mibsdk/stopped", ui.openPageKey());
        assertEquals(Arrays.asList("-:mibsdk/plugins", "+:mibsdk/stopped"), drain());
    }

    @Test
    void selectOnPageWithInfoEntriesDoesNotThrow() throws Exception {
        Map<String, UiPage> pages = new HashMap<String, UiPage>();
        pages.put("sdk/main", new UiPage("sdk/main", "Main", Arrays.asList(
                new UiEntry(UiEntry.INFO, null, "note", null, false, null),
                new UiEntry(UiEntry.ITEM, "go", "Go", null, false, null))));
        table.publish(new UiSnapshot(1, null, "", pages, 0, null));
        ui.select("sdk/main", "go");
        assertEquals("go", table.poll(0).entryKey());
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

    @Test
    void rendersIconTags() {
        UiIcon png = UiIcon.file("fruit", "/icons/fruit/0123456789abcdef.png", "0123456789abcdef", 20, 20);
        table.publish(new UiSnapshot(1, Arrays.asList(
                new UiRow("mibsdk/plugins", "Plugins", "mibsdk/plugins"),
                new UiRow("fruit/r1", "Label", "fruit/p", UiIcon.stock("star")),
                new UiRow("fruit/r2", "Fruit", "fruit/p", png)),
                "SAFE FR ok", new HashMap<String, UiPage>(), 0, null, Arrays.asList(
                        new UiStatusPart("SAFE", null, true),
                        new UiStatusPart("FR ok", png, false))));
        assertEquals(Arrays.asList("Plugins", "[star] Label", "[png:0123456789abcdef] Fruit"), ui.carRows());
        assertEquals(Arrays.asList("SAFE", "[png:0123456789abcdef] FR ok"), ui.statusParts());
        assertEquals("SAFE FR ok", ui.statusLine(), "the text-only line");
    }

    @Test
    void ownColourIconTag() {
        UiIcon own = UiIcon.file("fruit", "/icons/fruit/0123456789abcdef.png", "0123456789abcdef", 20, 20)
                .withOwnColours(true);
        table.publish(new UiSnapshot(1, Arrays.asList(
                new UiRow("mibsdk/plugins", "Plugins", "mibsdk/plugins"),
                new UiRow("fruit/r1", "Hello", "fruit/p", own)),
                "Hello", new HashMap<String, UiPage>(), 0, null, Arrays.asList(
                        new UiStatusPart("Hello", own, false))));
        assertEquals(Arrays.asList("[png:0123456789abcdef+own] Hello"), ui.statusParts());
        assertEquals(Arrays.asList("Plugins", "[png:0123456789abcdef] Hello"), ui.carRows(), "rows ignore it");
    }

    // ---- input entries (api 5) ----

    private static final UiIcon PNG = UiIcon.file("p", "/icons/p/0123456789abcdef.png", "0123456789abcdef", 20, 20);

    /** p/main with every input kind, and the choice sub-page p/main#lang the hub derives. */
    private void publishInputs(long version, long openSeq, String open) {
        Map<String, UiPage> pages = new HashMap<String, UiPage>();
        pages.put("p/main", new UiPage("p/main", "Inputs", Arrays.asList(
                new UiEntry(UiEntry.CHOICE, "lang", "Language", null, false, "p/main#lang")
                        .withOptions(new String[] {"English", "Deutsch"}).withNumber(0),
                new UiEntry(UiEntry.DROPDOWN, "lvl", "Level", null, false, null)
                        .withOptions(new String[] {"Off", "Low", "Mid"}).withNumber(2),
                new UiEntry(UiEntry.SLIDER, "bass", "Bass", null, false, null).withNumber(3).withRange(-6, 6, 1, true),
                new UiEntry(UiEntry.PROGRESS, "dl", "Download", null, false, null).withNumber(60),
                new UiEntry(UiEntry.ITEM, "apple", "Apple", "3", false, null).withIcon(PNG),
                new UiEntry(UiEntry.ITEM, "pear", "Pear", null, false, null).withIcon(UiIcon.stock("star")),
                new UiEntry(UiEntry.TOGGLE, "wifi", "Wi-Fi", null, false, null).withEnabled(false),
                new UiEntry(UiEntry.CHOICE, "region", "Region", null, false, "p/main#region")
                        .withOptions(new String[] {"EU"}).withNumber(0).withEnabled(false),
                new UiEntry(UiEntry.ITEM, "off", "Off", null, false, null).withEnabled(false),
                new UiEntry(UiEntry.DROPDOWN, "lvl2", "Level 2", null, false, null)
                        .withOptions(new String[] {"Off"}).withNumber(0).withEnabled(false),
                new UiEntry(UiEntry.SLIDER, "s2", "S2", null, false, null).withRange(0, 4, 1, false)
                        .withEnabled(false))));
        pages.put("p/main#lang", new UiPage("p/main#lang", "Language", Arrays.asList(
                new UiEntry(UiEntry.RADIO, "0", "English", null, true, null),
                new UiEntry(UiEntry.RADIO, "1", "Deutsch", null, false, null))));
        pages.put("p/main#region", new UiPage("p/main#region", "Region", Arrays.asList(
                new UiEntry(UiEntry.RADIO, "0", "EU", null, true, null))));
        table.publish(new UiSnapshot(version, null, "", pages, openSeq, open));
    }

    @Test
    void rendersEveryInputKind() {
        publishInputs(1, 0, null);
        assertEquals(Arrays.asList(
                "Language: English >",
                "Level: Mid v",
                "Bass: 3 [-6..6]",
                "Download: 60%",
                "[png:0123456789abcdef] Apple: 3",
                "[stock:star] Pear",
                "[off] Wi-Fi [disabled]",
                "Region: EU > [disabled]",
                "Off [disabled]",
                "Level 2: Off v [disabled]",
                "S2: 0 [0..4] [disabled]"), ui.render("p/main"));
        assertEquals(Arrays.asList("(*) English", "( ) Deutsch"), ui.render("p/main#lang"));
    }

    @Test
    void choiceRoundTrip() throws Exception {
        publishInputs(1, 1, "p/main");
        assertEquals("p/main", ui.openPageKey());
        drain();
        ui.select("p/main", "lang");
        assertEquals("p/main#lang", ui.openPageKey());
        assertEquals(Arrays.asList("-:p/main", "+:p/main#lang"), drain(), "navigated locally, no ENTRY_SELECTED");
        assertEquals(Arrays.asList("(*) English", "( ) Deutsch"), ui.render(ui.openPageKey()));
        ui.select("p/main#lang", "1");
        UiInput pick = table.poll(0);
        assertEquals(UiInput.ENTRY_SELECTED, pick.kind());
        assertEquals("p/main#lang", pick.pageKey());
        assertEquals("1", pick.entryKey());
        assertEquals("p/main", ui.openPageKey());
        assertEquals(Arrays.asList("-:p/main#lang", "+:p/main"), drain());
    }

    @Test
    void chooseSelectsTheOption() throws Exception {
        publishInputs(1, 1, "p/main");
        assertEquals("p/main", ui.openPageKey());
        drain();
        ui.choose("p/main", "lang", 1);
        assertEquals("p/main", ui.openPageKey(), "back on the page the choice is on");
        assertEquals(UiInput.PAGE_CLOSED, table.poll(0).kind()); // p/main left for the sub-page
        assertEquals(UiInput.PAGE_SHOWN, table.poll(0).kind()); // p/main#lang
        UiInput pick = table.poll(0);
        assertEquals(UiInput.ENTRY_SELECTED, pick.kind());
        assertEquals("p/main#lang", pick.pageKey());
        assertEquals("1", pick.entryKey());
        assertEquals(Arrays.asList("-:p/main#lang", "+:p/main"), drain());
    }

    @Test
    void chooseIgnoresDisabledAndNonChoiceEntries() throws Exception {
        publishInputs(1, 1, "p/main");
        assertEquals("p/main", ui.openPageKey());
        drain();
        ui.choose("p/main", "region", 0); // disabled
        ui.choose("p/main", "apple", 0); // not a choice
        assertEquals("p/main", ui.openPageKey());
        assertTrue(drain().isEmpty());
    }

    @Test
    void pickAndTurnSendRawValues() throws Exception {
        publishInputs(1, 0, null);
        ui.pick("p/main", "lvl", 1);
        UiInput in = table.poll(0);
        assertEquals(UiInput.VALUE_REQUESTED, in.kind());
        assertEquals("p/main", in.pageKey());
        assertEquals("lvl", in.entryKey());
        assertEquals(1, in.value());
        ui.turn("p/main", "bass", 99);
        in = table.poll(0);
        assertEquals(UiInput.VALUE_REQUESTED, in.kind());
        assertEquals("bass", in.entryKey());
        assertEquals(99, in.value(), "raw: the runtime clamps");
        ui.pick("p/main", "bass", 1); // not a dropdown
        ui.turn("p/main", "lvl", 1); // not a slider
        ui.select("p/main", "dl"); // progress: no input
        assertNull(table.poll(0));
    }

    @Test
    void disabledEntriesSendNothing() throws Exception {
        publishInputs(1, 1, "p/main");
        assertEquals("p/main", ui.openPageKey());
        drain();
        ui.toggle("p/main", "wifi");
        ui.select("p/main", "off");
        ui.select("p/main", "region");
        ui.pick("p/main", "lvl2", 0);
        ui.turn("p/main", "s2", 2);
        assertEquals("p/main", ui.openPageKey(), "a disabled choice does not open");
        assertTrue(drain().isEmpty());
    }

    // ---- dialogs and toasts (api 5) ----

    private void publishDialog(long version, mibsdk.platform.UiDialog d, mibsdk.platform.UiToast t) {
        table.publish(new UiSnapshot(version, null, "", new HashMap<String, UiPage>(), 0, null, null, d, t));
    }

    private java.util.List<String> dialogInputs() throws Exception {
        java.util.List<String> out = new java.util.ArrayList<String>();
        UiInput in;
        while ((in = table.poll(0)) != null) {
            out.add(in.kind() + ":" + in.pageKey() + ":" + in.entryKey() + ":" + in.value() + ":" + in.text());
        }
        return out;
    }

    @Test
    void dialogAndToastRendering() throws Exception {
        assertNull(ui.dialog());
        assertNull(ui.toast());
        assertNull(UiSnapshot.EMPTY.dialog());
        assertNull(UiSnapshot.down(1).toast());
        ui.answer(1); // nothing up: nothing sent
        ui.leave();
        assertTrue(dialogInputs().isEmpty());

        publishDialog(1, new mibsdk.platform.UiDialog(4, mibsdk.platform.UiDialog.CONFIRM, "p/main", "Reset?", "a\nb",
                "Yes", "No", 0), new mibsdk.platform.UiToast(2, "Saved", UiIcon.stock("check")));
        assertEquals("confirm:Reset?|a\nb|Yes|No", ui.dialog());
        assertEquals("[check] Saved", ui.toast());
        assertEquals(2, ui.toastSeq());
        ui.answer(1);
        assertNull(ui.dialog(), "answered");
        ui.answer(1); // once per dialog
        assertEquals(Arrays.asList(UiInput.DIALOG_DONE + ":p/main:4:1:null"), dialogInputs());

        publishDialog(2, new mibsdk.platform.UiDialog(5, mibsdk.platform.UiDialog.CONFIRM, "p/main", "T", "x", "Ja",
                "Nein", 0), null);
        assertNull(ui.toast());
        assertEquals(0, ui.toastSeq(), "no toast");
        ui.leave(); // BACK on a confirm is No
        assertEquals(Arrays.asList(UiInput.DIALOG_DONE + ":p/main:5:0:null"), dialogInputs());

        publishDialog(3, new mibsdk.platform.UiDialog(6, mibsdk.platform.UiDialog.SPELLER, "p/main", "Name", "Bo", null,
                null, 8), null);
        assertEquals("speller:Name|Bo", ui.dialog());
        ui.answer(1); // not a confirm: nothing
        ui.type("Bob");
        assertEquals("speller:Name|Bob", ui.dialog());
        ui.leave();
        assertNull(ui.dialog());
        assertEquals(Arrays.asList(UiInput.DIALOG_DONE + ":p/main:6:0:Bob"), dialogInputs());

        publishDialog(4, new mibsdk.platform.UiDialog(7, mibsdk.platform.UiDialog.SPELLER, "p/main", "Name", "Al", null,
                null, 8), null);
        assertEquals("speller:Name|Al", ui.dialog(), "typed text does not carry to the next speller");
        ui.leave();
        assertEquals(Arrays.asList(UiInput.DIALOG_DONE + ":p/main:7:0:Al"), dialogInputs());
    }
}
