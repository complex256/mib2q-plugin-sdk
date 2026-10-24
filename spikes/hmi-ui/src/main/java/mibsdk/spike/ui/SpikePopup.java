package mibsdk.spike.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.view.HMIView;
import de.audi.atip.hmi.view.IPartialPopupController;
import de.audi.tghu.system.hmi.evohigh.FontFactory;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.IconRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.LabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.PartialPopupRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.IconController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.PartialPopupController;

/**
 * Spike C (S1/S2): the SDK-owned partial popup 609600, a copy of stock popup 65
 * (SystemScreenBag3.partialPopupDebugInfo) moved left to (514,0,255,24), with three icons left of the label:
 * two Car-module SDK ids (custom PNGs via SpikeFactory.getImagePath) and one on the file-path ResourceLocatorModel.
 *
 * Constants are the decoded (byte-swapped back) values of the stock bag: scale factor (255, 24) for the 1x1 backdrop
 * (bitmap 251), and the palettes as in SpikePage.
 */
final class SpikePopup {
    static final int X = 514;
    static final int W = 255;
    static final int H = 24;
    /** The last built popup (for the "pop-slot" command). */
    static volatile PartialPopupController built;

    private SpikePopup() {
    }

    static IPartialPopupController build(SpikeFactory f, int t) {
        IFrameworkAccess fw = f.framework();

        IconController back = new IconController();
        IconRendererHigh backR = new IconRendererHigh(back);
        back.setRenderer(backR);
        back.setBitmaps(new int[] {251});
        back.setBounds(0, 0, W, H);
        back.setColorIndices(new int[] {1, 1, 4, 1});
        back.setProcessStateChange(2);
        backR.setScaleFactor(255.0f, 24.0f);

        IconController a = icon(new int[] {SpikeIds.ICON_POP_A}, 6);
        IconController b = icon(new int[] {SpikeIds.ICON_POP_B}, 30);
        IconController file = icon(null, 54);
        file.setModelID(SpikeIds.POPUP_FILE_MODEL);

        LabelController label = new LabelController();
        LabelRendererHigh labelR = new LabelRendererHigh(label);
        label.setRenderer(labelR);
        labelR.setFonts(FontFactory.getFonts(10, t, fw));     // the System font popup 65's label uses
        label.setModelID(SpikeIds.POPUP_LABEL_MODEL);
        label.setBounds(80, 7, W - 80 - 10, 20);
        label.setColorIndices(new int[] {1, 0, 4, 0});
        labelR.setAlignment(3, 4);

        PartialPopupController p = new PartialPopupController(SpikeIds.POPUP_SDK);
        p.setScreenFactory(f);
        PartialPopupRendererHigh pR = new PartialPopupRendererHigh(p);
        p.setRenderer(pR);
        pR.setFonts(FontFactory.getFonts(0, t, fw));
        p.setModelID(SpikeIds.POPUP_VISIBLE_MODEL);
        p.setBounds(X, 0, W, H);
        p.setColorIndices(new int[] {1, 2, 4, 0});
        int c4 = -6645094;
        int c5 = -5592406;
        int c6 = -4868683;
        p.setColorPalettes(new int[][] {
            {-1, 255, -1, -1, c4, c5, c6, c4, -1},
            {-1, 255, -65511, -5546136, c4, c5, c6, c4, -4703671},
            {-1, 255, -19456, -6123155, c4, c5, c6, c4, -6123155},
            {-1, 255, -14775126, -9594974, c4, c5, c6, c4, -9594974},
            {-1, 255, -10180096, -9200779, c4, c5, c6, c4, -9200779}});
        p.setConsumeDDSPress(4);
        p.setConsumeHKReturn(4);
        p.setConsumeKeyTurned(4);
        p.setID(SpikeIds.POPUP_SDK);
        p.setLayer(1);
        p.setSlot(5);
        p.setSmallStageType(3);
        p.setSurviveScreenChange(true);
        p.setType(7);
        p.setZpmPriority(250);
        p.setZpmSlot(12);
        p.add(back);
        p.add(a);
        p.add(b);
        p.add(file);
        p.add(label);
        // Sorted ids (findViews binary-searches).
        p.setViews(new int[] {SpikeIds.POPUP_VISIBLE_MODEL, SpikeIds.POPUP_FILE_MODEL, SpikeIds.POPUP_LABEL_MODEL},
                new HMIView[][] {{p}, {file}, {label}});
        int slot = SpikeProbes.popupSlot;
        if (slot != 5) {
            p.setSlot(slot);
        }
        built = p;
        return p;
    }

    /** A 20x20 icon at (x, 2); bitmaps may be null (model-driven). */
    static IconController icon(int[] bitmaps, int x) {
        IconController i = new IconController();
        i.setRenderer(new IconRendererHigh(i));
        if (bitmaps != null) {
            i.setBitmaps(bitmaps);
        }
        i.setBounds(x, 2, 20, 20);
        i.setProcessStateChange(2);
        return i;
    }
}
