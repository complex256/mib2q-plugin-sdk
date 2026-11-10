package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import mibsdk.api.StockIcons;
import mibsdk.platform.UiIcon;
import org.junit.jupiter.api.Test;

/** The ring page's glyphs: a StockIcons ring name maps to its cell of the HMI's 3D atlas. */
class RingScreenTest {

    @Test
    void stockRingNamesMapToTheirAtlasCells() {
        assertEquals(0, RingScreen.cell(UiIcon.stock(StockIcons.CAR)));
        assertEquals(1, RingScreen.cell(UiIcon.stock(StockIcons.RADIO)));
        assertEquals(2, RingScreen.cell(UiIcon.stock(StockIcons.MEDIA)));
        assertEquals(6, RingScreen.cell(UiIcon.stock(StockIcons.GLOBE)));
        assertEquals(9, RingScreen.cell(UiIcon.stock(StockIcons.SETTINGS)));
        assertEquals(13, RingScreen.cell(UiIcon.stock(StockIcons.SERVICE)));
        assertEquals(14, RingScreen.cell(UiIcon.stock(StockIcons.FLOWER)));
        assertEquals(16, RingScreen.cell(UiIcon.stock(StockIcons.WRENCH)));
        assertEquals(19, RingScreen.cell(UiIcon.stock(StockIcons.SMARTPHONE)));
    }

    @Test
    void anythingElseFallsBackToARealCell() {
        assertEquals(14, RingScreen.cell(null), "no icon");
        assertEquals(14, RingScreen.cell(UiIcon.stock(StockIcons.PLUG)), "a shape name has no atlas cell");
        assertEquals(14, RingScreen.cell(UiIcon.file("p", "../icons/p/abc.png", "abc", 72, 72)),
                "a plugin's own PNG has no atlas cell");
    }
}
