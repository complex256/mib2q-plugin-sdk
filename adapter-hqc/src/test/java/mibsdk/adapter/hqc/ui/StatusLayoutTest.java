package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiStatusPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatusLayoutTest {

    @TempDir
    File dir;

    /** A built-in set with {@code dot-20} and {@code cross-20}. */
    static BuiltinIcons builtins(File dir) throws IOException {
        Files.write(new File(dir, "d000000000000000.png").toPath(), new byte[] {1});
        Files.write(new File(dir, "c000000000000000.png").toPath(), new byte[] {1});
        Files.write(new File(dir, "index.properties").toPath(), ("cross-20=c000000000000000,20,20\n"
                + "dot-20=d000000000000000,20,20\n").getBytes(StandardCharsets.ISO_8859_1));
        return BuiltinIcons.read(dir);
    }

    private static UiStatusPart ind(String text) {
        return new UiStatusPart(text, null, true);
    }

    private static UiStatusPart item(String text, UiIcon icon) {
        return new UiStatusPart(text, icon, false);
    }

    private static UiIcon file(String sha16) {
        return UiIcon.file("hello", HqcUiIds.INSTALL_ROOT + "/icons/hello/" + sha16 + ".png", sha16, 20, 20);
    }

    @Test
    void cellsFollowTheUnitGrid() {
        List<UiStatusPart> parts = new ArrayList<UiStatusPart>();
        parts.add(ind("SAFE"));
        parts.add(item("C801", file("aaaa000000000000")));
        for (int i = 0; i < 6; i++) {
            parts.add(item("I" + i, null)); // 7 items: the 7th is dropped
        }
        IconIds ids = new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_LAST);
        StatusLayout l = StatusLayout.of(parts, ids, BuiltinIcons.NONE);

        assertEquals(6, l.indicators().labelX);
        assertEquals("SAFE", l.indicators().text);
        StatusLayout.Cell c = l.cell(0);
        assertEquals(6 + 40 + 10, c.iconX);
        assertEquals(c.iconX + 20, c.labelX);
        assertEquals("C801", c.text);
        assertEquals(HqcUiIds.INSTALL_ROOT + "/icons/hello/aaaa000000000000.png", c.filePath,
                "a file icon is drawn from its absolute path");
        assertEquals(-1, c.stockIconId);
        assertEquals(HqcUiIds.POPUP_SDK_W - HqcUiIds.STATUS_PAD_PX - c.labelX, c.labelW);
        assertEquals(c.labelX + 40 + 10, l.cell(1).labelX, "a space, then the next item");
        assertEquals("I0", l.cell(1).text);
        assertEquals("I4", l.cell(5).text, "the 7th item (I5) has no cell");
        for (int i = 0; i < HqcUiIds.STATUS_CELLS; i++) {
            assertTrue(l.cell(i).labelW >= 0, "cell " + i);
        }
        assertTrue(l.indicators().labelW >= 0);
    }

    @Test
    void aFullLineEndsInsideThePopup() {
        List<UiStatusPart> parts = new ArrayList<UiStatusPart>();
        parts.add(ind("SAFE PDC R OFF"));
        parts.add(item("abcdefg", null));
        parts.add(item("x", null)); // 14 + 1 + 7 + 1 + 1 = 24 units
        StatusLayout l = StatusLayout.of(parts, null, null);
        int end = l.cell(1).labelX + l.cell(1).text.length() * HqcUiIds.STATUS_CHAR_PX;
        assertEquals(6 + 240, end);
        assertTrue(end <= HqcUiIds.POPUP_SDK_W - HqcUiIds.STATUS_PAD_PX);
    }

    @Test
    void aLineWithoutIndicatorsStartsWithItsFirstItem() {
        List<UiStatusPart> parts = new ArrayList<UiStatusPart>();
        parts.add(item("TS up", file("aaaa000000000000")));
        StatusLayout l = StatusLayout.of(parts, null, null);
        assertEquals("", l.indicators().text);
        assertEquals(6, l.cell(0).iconX);
        assertEquals(26, l.cell(0).labelX);
        assertEquals("", l.cell(1).text);
        assertNull(l.cell(1).filePath);
    }

    @Test
    void aStockIconIsItsBuiltinTwentyPxIdAndAFileIconItsPath() throws IOException {
        BuiltinIcons b = builtins(dir);
        IconIds ids = new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_LAST);
        List<UiStatusPart> parts = new ArrayList<UiStatusPart>();
        parts.add(item("TS up", UiIcon.stock("dot")));
        parts.add(item("MQ", UiIcon.stock("star"))); // not installed: no id, but the icon's space stays
        StatusLayout l = StatusLayout.of(parts, ids, b);
        assertEquals(HqcUiIds.ICON_ID_FIRST, l.cell(0).stockIconId);
        assertEquals(new File(dir, "d000000000000000.png").getPath(), ids.fileFor(l.cell(0).stockIconId));
        assertNull(l.cell(0).filePath, "a stock icon is drawn by id (tinted)");
        assertEquals(-1, l.cell(1).stockIconId);
        assertEquals(l.cell(1).iconX + HqcUiIds.STATUS_ICON_ADVANCE_PX, l.cell(1).labelX);
    }

    @Test
    void textChangeOfSameLengthKeepsGeometry() {
        List<UiStatusPart> a = new ArrayList<UiStatusPart>();
        a.add(ind("SAFE"));
        a.add(item("TS up", file("aaaa000000000000")));
        List<UiStatusPart> b = new ArrayList<UiStatusPart>();
        b.add(ind("SAFE"));
        b.add(item("TS dn", file("bbbb000000000000")));
        List<UiStatusPart> c = new ArrayList<UiStatusPart>();
        c.add(ind("SAFE"));
        c.add(item("TS down", file("aaaa000000000000")));
        a.add(item("MQ", null));
        b.add(item("MQ", null));
        c.add(item("MQ", null)); // the cell after a longer text moves
        StatusLayout la = StatusLayout.of(a, null, null);
        assertTrue(la.sameGeometry(StatusLayout.of(b, null, null)));
        assertTrue(la.sameIcons(StatusLayout.of(b, null, null)), "file icons are models, not bitmaps");
        assertFalse(la.sameGeometry(StatusLayout.of(c, null, null)), "a longer text moves the bounds");
    }

    @Test
    void stockIconSwapKeepsGeometryButNotIcons() throws IOException {
        BuiltinIcons bi = builtins(dir);
        IconIds ids = new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_LAST);
        List<UiStatusPart> a = new ArrayList<UiStatusPart>();
        a.add(item("TS up", UiIcon.stock("dot")));
        List<UiStatusPart> b = new ArrayList<UiStatusPart>();
        b.add(item("TS up", UiIcon.stock("cross")));
        StatusLayout la = StatusLayout.of(a, ids, bi);
        StatusLayout lb = StatusLayout.of(b, ids, bi);
        assertTrue(la.sameGeometry(lb));
        assertFalse(la.sameIcons(lb));
        assertTrue(la.sameIcons(StatusLayout.of(a, ids, bi)), "the same content keeps its id");
    }

    @Test
    void cellCarriesOwnColoursAndSameIconsSeesIt() {
        List<UiStatusPart> a = new ArrayList<UiStatusPart>();
        a.add(item("TS up", file("aaaa000000000000")));
        List<UiStatusPart> b = new ArrayList<UiStatusPart>();
        b.add(item("TS up", file("aaaa000000000000").withOwnColours(true)));
        StatusLayout la = StatusLayout.of(a, null, null);
        StatusLayout lb = StatusLayout.of(b, null, null);
        assertFalse(la.cell(0).ownColours);
        assertTrue(lb.cell(0).ownColours);
        assertEquals(la.cell(0).filePath, lb.cell(0).filePath, "the same file either way");
        assertTrue(la.sameGeometry(lb));
        assertFalse(la.sameIcons(lb), "a colour toggle is an icon change");
        assertTrue(lb.sameIcons(StatusLayout.of(b, null, null)));
    }

    /** The icon cell is the runtime's ICON_CHARS wide (StatusLine is package-private there: read reflectively). */
    @Test
    void iconCellIsTheRuntimesIconChars() throws Exception {
        Field f = Class.forName("mibsdk.runtime.StatusLine").getDeclaredField("ICON_CHARS");
        f.setAccessible(true);
        assertEquals(f.getInt(null), HqcUiIds.STATUS_ICON_CHARS);
        assertEquals(20, HqcUiIds.STATUS_ICON_PX);
        assertEquals(HqcUiIds.STATUS_ICON_CHARS * HqcUiIds.STATUS_CHAR_PX, HqcUiIds.STATUS_ICON_ADVANCE_PX);
    }

    /**
     * Retuning {@code STATUS_CHAR_PX} on the bench moves only the layout advance: the glyph size (the built-in
     * {@code <name>-20} lookup and the icon bounds) is the literal 20, or every stock status icon would vanish. The
     * constants are compile-time, so this reads the source: no expression, and the lookup and bounds use it.
     */
    @Test
    void retuningCharPxCannotChangeTheGlyphSize() throws IOException {
        String ids = source("HqcUiIds");
        assertTrue(ids.matches("(?s).*\\bSTATUS_ICON_PX = 20;.*"), "STATUS_ICON_PX is the literal 20");
        assertTrue(ids.matches("(?s).*\\bSTATUS_ICON_ADVANCE_PX = STATUS_ICON_CHARS \\* STATUS_CHAR_PX;.*"));
        String layout = source("StatusLayout");
        assertTrue(layout.contains("builtins.get(icon.name(), HqcUiIds.STATUS_ICON_PX, false)"), "the glyph lookup");
        assertTrue(layout.contains("x += HqcUiIds.STATUS_ICON_ADVANCE_PX;"), "the advance");
        assertFalse(layout.contains("x += HqcUiIds.STATUS_ICON_PX;"), "the glyph size is not the advance");
        String popup = source("SdkStatusPopup");
        assertEquals(2, count(popup, "HqcUiIds.STATUS_ICON_PX, HqcUiIds.STATUS_ICON_PX)"), "both icons' bounds");
        assertFalse(popup.contains("STATUS_ICON_ADVANCE_PX"));
    }

    private static String source(String name) throws IOException {
        File f = new File("src/main/java/mibsdk/adapter/hqc/ui/" + name + ".java");
        assertTrue(f.isFile(), "run from the adapter-hqc project dir: " + f.getAbsolutePath());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int at = s.indexOf(sub); at >= 0; at = s.indexOf(sub, at + 1)) {
            n++;
        }
        return n;
    }
}
