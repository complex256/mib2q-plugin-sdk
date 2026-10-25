package mibsdk.adapter.hqc.ui;

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
 * The SDK status popup {@link HqcUiIds#POPUP_SDK_STATUS}: spike C's recipe (a copy of stock popup 65,
 * SystemScreenBag3.partialPopupDebugInfo) at ({@link HqcUiIds#POPUP_SDK_X}, 0, {@link HqcUiIds#POPUP_SDK_W},
 * {@link HqcUiIds#POPUP_SDK_H}) in slot {@link HqcUiIds#POPUP_SDK_SLOT}, beside popup 65 (Boot B). It holds a backdrop,
 * the indicator label, and {@link HqcUiIds#STATUS_CELLS} cells, each an id icon (a built-in, drawn by image id),
 * a file icon on its ResourceLocatorModel (a package PNG) and a label on its LabelModel. Each cell's icons are tinted
 * with the app colour (bench B10, no colour indices) or, when the cell's {@link StatusLayout.Cell#ownColours} is set,
 * drawn in their own colours ({@link HqcUiIds#STATUS_ICON_OWN_COLOURS}); a rebuilt popup applies it from
 * {@link #wanted} like the id icons.
 *
 * <p>Built on HMIEvent by {@link SdkCarScreenFactory#getPartialPopup} with the bounds and id icons of {@link #wanted};
 * later changes come from {@link HmiWidgets} through {@link #current}. HMIEvent only, apart from the two volatile
 * references. No I/O, no thread.
 */
final class SdkStatusPopup {

    /** The popup built last, or null; its widgets are touched on HMIEvent only. */
    static volatile SdkStatusPopup current;
    /** The layout {@code mibsdk-ui} wants now: a popup built later starts from it. */
    static volatile StatusLayout wanted = StatusLayout.EMPTY;

    private static final int ICON_Y = 2;
    private static final int LABEL_Y = 7;
    private static final int LABEL_H = 20;

    private final LabelController[] labels;
    private final IconController[] idIcons;
    private final IconController[] fileIcons;

    /**
     * {@code labels}: the indicator label, then one per cell; {@code idIcons} and {@code fileIcons}: one per cell
     * ({@link HqcUiIds#STATUS_CELLS}).
     */
    SdkStatusPopup(LabelController[] labels, IconController[] idIcons, IconController[] fileIcons) {
        this.labels = labels;
        this.idIcons = idIcons;
        this.fileIcons = fileIcons;
    }

    /** Builds the popup from {@link #wanted} and makes it {@link #current}. Throws on any failure (the caller logs). */
    static IPartialPopupController build(SdkCarScreenFactory f, int terminal) {
        IFrameworkAccess fw = f.framework();
        int w = HqcUiIds.POPUP_SDK_W;
        int h = HqcUiIds.POPUP_SDK_H;

        IconController back = new IconController();
        IconRendererHigh backR = new IconRendererHigh(back);
        back.setRenderer(backR);
        back.setBitmaps(new int[] {HqcUiIds.POPUP_BACKDROP_BITMAP});
        back.setBounds(0, 0, w, h);
        back.setColorIndices(new int[] {1, 1, 4, 1});
        back.setProcessStateChange(2);
        backR.setScaleFactor((float) w, (float) h); // the backdrop is 1x1

        int cells = HqcUiIds.STATUS_CELLS;
        LabelController[] labels = new LabelController[cells + 1];
        IconController[] ids = new IconController[cells];
        IconController[] files = new IconController[cells];
        for (int i = 0; i < labels.length; i++) {
            LabelController l = new LabelController();
            LabelRendererHigh lr = new LabelRendererHigh(l);
            l.setRenderer(lr);
            lr.setFonts(FontFactory.getFonts(10, terminal, fw)); // the System font popup 65's label uses
            l.setModelID(HqcUiIds.STATUS_LABEL_MODEL_0 + i);
            l.setColorIndices(new int[] {1, 0, 4, 0});
            lr.setAlignment(1, 4); // ALIGN_LEFT: the layout places each label from its left edge (3 is ALIGN_RIGHT)
            labels[i] = l;
        }
        for (int i = 0; i < cells; i++) {
            ids[i] = icon();
            files[i] = icon();
            files[i].setModelID(HqcUiIds.STATUS_FILE_ICON_MODEL_1 + i);
        }
        SdkStatusPopup s = new SdkStatusPopup(labels, ids, files);
        StatusLayout l = wanted;
        s.place(l);
        s.icons(l, StatusLayout.EMPTY);

        PartialPopupController p = new PartialPopupController(HqcUiIds.POPUP_SDK_STATUS);
        p.setScreenFactory(f);
        PartialPopupRendererHigh pR = new PartialPopupRendererHigh(p);
        p.setRenderer(pR);
        pR.setFonts(FontFactory.getFonts(0, terminal, fw));
        p.setModelID(HqcUiIds.STATUS_POPUP_VISIBLE_MODEL);
        p.setBounds(HqcUiIds.POPUP_SDK_X, 0, w, h);
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
        p.setID(HqcUiIds.POPUP_SDK_STATUS);
        p.setLayer(1);
        p.setSlot(HqcUiIds.POPUP_SDK_SLOT);
        p.setSmallStageType(3);
        p.setSurviveScreenChange(true);
        p.setType(HqcUiIds.POPUP_SDK_TYPE);
        p.setZpmPriority(HqcUiIds.POPUP_SDK_PRIORITY);
        p.setZpmSlot(HqcUiIds.POPUP_SDK_ZPM_SLOT);
        p.add(back);
        for (int i = 0; i < cells; i++) {
            p.add(ids[i]);
            p.add(files[i]);
        }
        for (int i = 0; i < labels.length; i++) {
            p.add(labels[i]);
        }
        // Sorted ids (findViews binary-searches): the visibility model, the labels, the file icons.
        int[] viewIds = new int[1 + labels.length + cells];
        HMIView[][] views = new HMIView[viewIds.length][];
        viewIds[0] = HqcUiIds.STATUS_POPUP_VISIBLE_MODEL;
        views[0] = new HMIView[] {p};
        for (int i = 0; i < labels.length; i++) {
            viewIds[1 + i] = HqcUiIds.STATUS_LABEL_MODEL_0 + i;
            views[1 + i] = new HMIView[] {labels[i]};
        }
        for (int i = 0; i < cells; i++) {
            viewIds[1 + labels.length + i] = HqcUiIds.STATUS_FILE_ICON_MODEL_1 + i;
            views[1 + labels.length + i] = new HMIView[] {files[i]};
        }
        p.setViews(viewIds, views);
        current = s;
        return p;
    }

    /** A 20x20 icon; its bounds come from the layout. */
    private static IconController icon() {
        IconController i = new IconController();
        i.setRenderer(new IconRendererHigh(i));
        i.setProcessStateChange(2);
        return i;
    }

    /** Sets every label's and icon's bounds from {@code l}. HMIEvent. */
    void place(StatusLayout l) {
        label(labels[0], l.indicators());
        for (int i = 0; i < idIcons.length; i++) {
            StatusLayout.Cell c = l.cell(i);
            idIcons[i].setBounds(c.iconX, ICON_Y, HqcUiIds.STATUS_ICON_PX, HqcUiIds.STATUS_ICON_PX);
            fileIcons[i].setBounds(c.iconX, ICON_Y, HqcUiIds.STATUS_ICON_PX, HqcUiIds.STATUS_ICON_PX);
            label(labels[1 + i], c);
        }
    }

    private static void label(LabelController l, StatusLayout.Cell c) {
        l.setBounds(c.labelX, LABEL_Y, c.labelW, LABEL_H);
    }

    /**
     * Sets the id icon of each cell whose built-in icon differs from {@code last}: its image id, or an empty array (no
     * icon), and marks it for a redraw. Of each cell whose colour flag differs from {@code last}, sets both icons'
     * colour indices ({@link HqcUiIds#STATUS_ICON_OWN_COLOURS} for own colours, null for tinted) and marks both for a
     * redraw. HMIEvent.
     */
    void icons(StatusLayout l, StatusLayout last) {
        for (int i = 0; i < idIcons.length; i++) {
            StatusLayout.Cell c = l.cell(i);
            int id = c.stockIconId;
            if (id != last.cell(i).stockIconId) {
                idIcons[i].setBitmaps(id >= 0 ? new int[] {id} : new int[0]);
                idIcons[i].setCompositesDirty(true); // setBitmaps only stores the array: redraw it now, not at the next screen
            }
            if (c.ownColours != last.cell(i).ownColours) {
                colours(idIcons[i], c.ownColours);
                colours(fileIcons[i], c.ownColours);
            }
        }
    }

    /** Own colours (plate entry 0) or tinted (no indices: the renderer's tint), redrawn now. */
    private static void colours(IconController icon, boolean own) {
        icon.setColorIndices(own ? HqcUiIds.STATUS_ICON_OWN_COLOURS : null);
        icon.setCompositesDirty(true);
    }
}
