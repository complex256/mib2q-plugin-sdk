package mibsdk.adapter.hqc.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.tghu.system.hmi.evohigh.FontFactory;
import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.IconRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.LabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.PartialPopupRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.IconController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.PartialPopupController;

/**
 * The SDK popups' one widget recipe, shared by the status popup ({@link SdkStatusPopup}) and the toast
 * ({@link SdkToastPopup}): spike C's copy of stock popup 65 (SystemScreenBag3.partialPopupDebugInfo), as proven on the
 * bench. A fix to the recipe (a palette, the C1 repaint) reaches both. HMIEvent only; no I/O, no thread.
 */
final class SdkPopups {

    private SdkPopups() {
    }

    /** The popup backdrop: the 1x1 bitmap {@link HqcUiIds#POPUP_BACKDROP_BITMAP} scaled to {@code w} x {@code h}. */
    static IconController backdrop(int w, int h) {
        IconController back = new IconController();
        IconRendererHigh backR = new IconRendererHigh(back);
        back.setRenderer(backR);
        back.setBitmaps(new int[] {HqcUiIds.POPUP_BACKDROP_BITMAP});
        back.setBounds(0, 0, w, h);
        back.setColorIndices(new int[] {1, 1, 4, 1});
        back.setProcessStateChange(2);
        backR.setScaleFactor((float) w, (float) h); // the backdrop is 1x1
        return back;
    }

    /**
     * A label on {@code modelId} in the System font popup 65's label uses, aligned {@code hAlign} (1 ALIGN_LEFT, 3
     * ALIGN_RIGHT in the stock {@code Alignment}).
     */
    static LabelController label(int modelId, int hAlign, int terminal, IFrameworkAccess fw) {
        LabelController l = new LabelController();
        LabelRendererHigh lr = new LabelRendererHigh(l);
        l.setRenderer(lr);
        lr.setFonts(FontFactory.getFonts(10, terminal, fw));
        l.setModelID(modelId);
        l.setColorIndices(new int[] {1, 0, 4, 0});
        lr.setAlignment(hAlign, 4);
        return l;
    }

    /** A 20x20 icon; its bounds come from the popup's layout. */
    static IconController icon() {
        IconController i = new IconController();
        i.setRenderer(new IconRendererHigh(i));
        i.setProcessStateChange(2);
        return i;
    }

    /**
     * Configures popup {@code p} (its children are added by the caller after): the renderer and fonts, visibility model
     * {@code modelId}, the bounds, the palette and the popup-65 flags, then the id, slot, type and ZPM slot/priority.
     */
    static void configure(PartialPopupController p, SdkCarScreenFactory f, int terminal, int id, int modelId, int x,
            int y, int w, int h, int slot, int type, int zpmPriority, int zpmSlot) {
        IFrameworkAccess fw = f.framework();
        p.setScreenFactory(f);
        PartialPopupRendererHigh pR = new PartialPopupRendererHigh(p);
        p.setRenderer(pR);
        pR.setFonts(FontFactory.getFonts(0, terminal, fw));
        p.setModelID(modelId);
        p.setBounds(x, y, w, h);
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
        p.setID(id);
        p.setLayer(1);
        p.setSlot(slot);
        p.setSmallStageType(3);
        p.setSurviveScreenChange(true);
        p.setType(type);
        p.setZpmPriority(zpmPriority);
        p.setZpmSlot(zpmSlot);
    }

    /**
     * Own colours (plate entry 0, {@link HqcUiIds#STATUS_ICON_OWN_COLOURS}) or tinted (no indices: the renderer's
     * tint), marked for the next paint.
     */
    static void colours(IconController icon, boolean own) {
        icon.setColorIndices(own ? HqcUiIds.STATUS_ICON_OWN_COLOURS : null);
        icon.setCompositesDirty(true);
    }

    /**
     * Asks the HMI to paint the dirty widgets now: the popup's stock triggerRepaint (the current screen's
     * doCheckedRepaint, bench C1). {@code popup} null (host tests): nothing.
     */
    static void redraw(AbstractWidget popup) {
        if (popup != null) {
            popup.triggerRepaint();
        }
    }
}
