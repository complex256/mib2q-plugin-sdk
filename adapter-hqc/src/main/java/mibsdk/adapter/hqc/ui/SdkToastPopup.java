package mibsdk.adapter.hqc.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.view.HMIView;
import de.audi.atip.hmi.view.IPartialPopupController;
import de.audi.tghu.system.hmi.evohigh.FontFactory;
import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.IconRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.LabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.PartialPopupRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.IconController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.PartialPopupController;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiToast;

/**
 * The SDK toast popup {@link HqcUiIds#POPUP_SDK_TOAST}: the status popup's recipe ({@link SdkStatusPopup}) at
 * ({@link HqcUiIds#POPUP_TOAST_X}, 0, {@link HqcUiIds#POPUP_TOAST_W}, {@link HqcUiIds#POPUP_TOAST_H}) in its own slot
 * {@link HqcUiIds#POPUP_TOAST_SLOT}, left of the status popup. It holds a backdrop, one label on
 * {@link HqcUiIds#TOAST_LABEL_MODEL}, right-aligned, and the icon just left of the text: an id icon (a built-in, drawn
 * by image id, as a status cell's) and a file icon on {@link HqcUiIds#TOAST_FILE_ICON_MODEL} (a package PNG), at most
 * {@link HqcUiIds#STATUS_ICON_PX} square. Its visibility model is {@link HqcUiIds#TOAST_VISIBLE_MODEL}: the toast never
 * writes {@link HqcUiIds#STATUS_POPUP_VISIBLE_MODEL} or any status model.
 *
 * <p>Models are written on {@code mibsdk-ui} only ({@link Models}, by {@code UiApplier}); the icon's bounds, its image
 * id and its colours are widget state ({@link Part}), applied by the one HMI job ({@link HmiWidgets}), which asks for
 * one repaint after a change, as the status popup's ({@link #redraw}). Built on HMIEvent by
 * {@link SdkCarScreenFactory#getPartialPopup} from {@link #wanted}. HMIEvent only, apart from the volatile references.
 * No I/O, no thread.
 */
final class SdkToastPopup {

    /** The popup built last, or null; its widgets are touched on HMIEvent only. */
    static volatile SdkToastPopup current;
    /** The toast {@code mibsdk-ui} wants drawn now: a popup built later starts from it. */
    static volatile Part wanted = Part.NONE;
    /**
     * True from {@code mibsdk-ui}'s visibility-1 write of a toast to its visibility-0 write: a toast is up. Read on
     * HMIEvent by {@link StatusPopupController}.
     */
    static volatile boolean up;

    private static final int ICON_Y = 2;
    private static final int LABEL_Y = 7;
    private static final int LABEL_H = 20;
    /** The text ends here, inside the right padding. */
    private static final int TEXT_RIGHT = HqcUiIds.POPUP_TOAST_W - HqcUiIds.STATUS_PAD_PX;

    /** The toast's Car models (the bank's, {@link SdkCarModelBank#toastModels}); written on {@code mibsdk-ui} only. */
    interface Models {
        void label(String text);

        /** The file icon: an absolute path, or null for none. */
        void fileIcon(String path);

        void visible(boolean on);
    }

    /** Where the models come from; null while they cannot be had (no SDK bank). */
    interface Source {
        Models get();
    }

    /**
     * The toast's widget state: its text (for the icon's place), its built-in icon's image id, its file icon's path,
     * the colour flag and whether it is wanted visible. Immutable; built on {@code mibsdk-ui} ({@link #of} gives a
     * built-in its image id).
     */
    static final class Part {
        static final Part NONE = new Part("", -1, null, false, false);

        final String text;
        /** A built-in icon's image id (its {@code <name>-20}, drawn by id); -1 for none. */
        final int stockIconId;
        /** A package icon's absolute installed path; null for none. */
        final String filePath;
        /** The icon in its own colours; false: tinted with the app colour (as a status cell). */
        final boolean ownColours;
        final boolean visible;

        Part(String text, int stockIconId, String filePath, boolean ownColours, boolean visible) {
            this.text = text == null ? "" : text;
            this.stockIconId = stockIconId;
            this.filePath = filePath;
            this.ownColours = ownColours;
            this.visible = visible;
        }

        /** {@code t} shown; a built-in icon gets its image id from {@code ids} (null ids or builtins: no id). */
        static Part of(UiToast t, IconIds ids, BuiltinIcons builtins) {
            UiIcon icon = t.icon();
            int id = -1;
            if (icon != null && icon.kind() == UiIcon.STOCK && ids != null && builtins != null) {
                id = DrawerSlots.id(builtins.get(icon.name(), HqcUiIds.STATUS_ICON_PX, false), ids);
            }
            String path = icon != null && icon.kind() == UiIcon.FILE ? icon.path() : null;
            return new Part(t.text(), id, path, icon != null && icon.ownColours(), true);
        }

        /** The same toast, wanted hidden. */
        Part hidden() {
            return new Part(text, stockIconId, filePath, ownColours, false);
        }

        /**
         * The icon's x: one icon's advance ({@link HqcUiIds#STATUS_ICON_ADVANCE_PX}) left of the text, which ends at
         * the right padding and is {@link HqcUiIds#STATUS_CHAR_PX} per character (the status line's grid); never left
         * of the left padding.
         */
        int iconX() {
            return Math.max(HqcUiIds.STATUS_PAD_PX,
                    TEXT_RIGHT - text.length() * HqcUiIds.STATUS_CHAR_PX - HqcUiIds.STATUS_ICON_ADVANCE_PX);
        }

        /** True if both are hidden, or both visible and drawn alike (the icon's place, id and colours). */
        boolean sameDrawn(Part o) {
            if (!visible || !o.visible) {
                return visible == o.visible;
            }
            return iconX() == o.iconX() && stockIconId == o.stockIconId && ownColours == o.ownColours;
        }
    }

    private final LabelController label;
    private final IconController idIcon;
    private final IconController fileIcon;
    /** The popup controller {@link #redraw} asks for a repaint; null in host tests that need none. */
    private final AbstractWidget popup;

    SdkToastPopup(LabelController label, IconController idIcon, IconController fileIcon, AbstractWidget popup) {
        this.label = label;
        this.idIcon = idIcon;
        this.fileIcon = fileIcon;
        this.popup = popup;
    }

    /** Builds the popup from {@link #wanted} and makes it {@link #current}. Throws on any failure (the caller logs). */
    static IPartialPopupController build(SdkCarScreenFactory f, int terminal) {
        IFrameworkAccess fw = f.framework();
        int w = HqcUiIds.POPUP_TOAST_W;
        int h = HqcUiIds.POPUP_TOAST_H;

        IconController back = new IconController();
        IconRendererHigh backR = new IconRendererHigh(back);
        back.setRenderer(backR);
        back.setBitmaps(new int[] {HqcUiIds.POPUP_BACKDROP_BITMAP});
        back.setBounds(0, 0, w, h);
        back.setColorIndices(new int[] {1, 1, 4, 1});
        back.setProcessStateChange(2);
        backR.setScaleFactor((float) w, (float) h); // the backdrop is 1x1

        LabelController l = new LabelController();
        LabelRendererHigh lr = new LabelRendererHigh(l);
        l.setRenderer(lr);
        lr.setFonts(FontFactory.getFonts(10, terminal, fw)); // the status popup's font
        l.setModelID(HqcUiIds.TOAST_LABEL_MODEL);
        l.setColorIndices(new int[] {1, 0, 4, 0});
        lr.setAlignment(3, 4); // ALIGN_RIGHT: the text ends at the right padding, the icon goes left of it
        l.setBounds(HqcUiIds.STATUS_PAD_PX, LABEL_Y, TEXT_RIGHT - HqcUiIds.STATUS_PAD_PX, LABEL_H);
        IconController id = icon();
        IconController file = icon();
        file.setModelID(HqcUiIds.TOAST_FILE_ICON_MODEL);

        PartialPopupController p = new PartialPopupController(HqcUiIds.POPUP_SDK_TOAST);
        SdkToastPopup s = new SdkToastPopup(l, id, file, p);
        Part want = wanted;
        s.place(want);
        s.icons(want, null); // no redraw: the popup is not shown yet, and showing it paints it
        p.setScreenFactory(f);
        PartialPopupRendererHigh pR = new PartialPopupRendererHigh(p);
        p.setRenderer(pR);
        pR.setFonts(FontFactory.getFonts(0, terminal, fw));
        p.setModelID(HqcUiIds.TOAST_VISIBLE_MODEL);
        p.setBounds(HqcUiIds.POPUP_TOAST_X, HqcUiIds.POPUP_TOAST_Y, w, h);
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
        p.setID(HqcUiIds.POPUP_SDK_TOAST);
        p.setLayer(1);
        p.setSlot(HqcUiIds.POPUP_TOAST_SLOT);
        p.setSmallStageType(3);
        p.setSurviveScreenChange(true);
        p.setType(HqcUiIds.POPUP_TOAST_TYPE);
        p.setZpmPriority(HqcUiIds.POPUP_TOAST_PRIORITY);
        p.setZpmSlot(HqcUiIds.POPUP_TOAST_ZPM_SLOT);
        p.add(back);
        p.add(id);
        p.add(file);
        p.add(l);
        // Sorted ids (findViews binary-searches): the visibility model, the label, the file icon.
        p.setViews(new int[] {HqcUiIds.TOAST_VISIBLE_MODEL, HqcUiIds.TOAST_LABEL_MODEL, HqcUiIds.TOAST_FILE_ICON_MODEL},
                new HMIView[][] {new HMIView[] {p}, new HMIView[] {l}, new HMIView[] {file}});
        current = s;
        return p;
    }

    /** A 20x20 icon; its x comes from the toast's text. */
    private static IconController icon() {
        IconController i = new IconController();
        i.setRenderer(new IconRendererHigh(i));
        i.setProcessStateChange(2);
        return i;
    }

    /** Puts both icons left of {@code p}'s text (stock setBounds marks a moved widget dirty). HMIEvent. */
    void place(Part p) {
        int x = p.iconX();
        idIcon.setBounds(x, ICON_Y, HqcUiIds.STATUS_ICON_PX, HqcUiIds.STATUS_ICON_PX);
        fileIcon.setBounds(x, ICON_Y, HqcUiIds.STATUS_ICON_PX, HqcUiIds.STATUS_ICON_PX);
    }

    /**
     * Sets the id icon's image id (or none) when it differs from {@code last}'s, and both icons' colour indices when
     * the colour flag differs ({@code last} null: both), each marked for a redraw. True if anything changed: the caller
     * then calls {@link #redraw}. HMIEvent.
     */
    boolean icons(Part want, Part last) {
        boolean changed = false;
        if (last == null || want.stockIconId != last.stockIconId) {
            idIcon.setBitmaps(want.stockIconId >= 0 ? new int[] {want.stockIconId} : new int[0]);
            idIcon.setCompositesDirty(true); // setBitmaps only stores the array
            changed = true;
        }
        if (last == null || want.ownColours != last.ownColours) {
            colours(idIcon, want.ownColours);
            colours(fileIcon, want.ownColours);
            changed = true;
        }
        return changed;
    }

    /** Asks the HMI to paint the dirty widgets now (the popup's stock triggerRepaint, as {@link SdkStatusPopup}). */
    void redraw() {
        if (popup != null) {
            popup.triggerRepaint();
        }
    }

    private static void colours(IconController icon, boolean own) {
        icon.setColorIndices(own ? HqcUiIds.STATUS_ICON_OWN_COLOURS : null);
        icon.setCompositesDirty(true);
    }
}
