package mibsdk.adapter.hqc.ui;

import de.audi.atip.hmi.view.HMIView;
import de.audi.atip.hmi.view.IPartialPopupController;
import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
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
 * one repaint after a change, as the status popup's ({@link #redraw}). The widget recipe is the status popup's, shared
 * through {@link SdkPopups}. Built on HMIEvent by
 * {@link SdkCarScreenFactory#getPartialPopup} from {@link #wanted}. HMIEvent only, apart from the volatile references.
 * No I/O, no thread.
 */
final class SdkToastPopup {

    /** The popup built last, or null; its widgets are touched on HMIEvent only. */
    static volatile SdkToastPopup current;
    /** The toast {@code mibsdk-ui} wants drawn now: a popup built later starts from it. */
    static volatile Part wanted = Part.NONE;
    /**
     * True while a toast is on screen: {@code mibsdk-ui} wrote its visibility 1 (not yet 0) and the popup is built
     * ({@code SdkGlobals.toastPopup} 2). Read on HMIEvent by {@link StatusPopupController}'s log line.
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
        int w = HqcUiIds.POPUP_TOAST_W;
        int h = HqcUiIds.POPUP_TOAST_H;

        IconController back = SdkPopups.backdrop(w, h);
        // ALIGN_RIGHT: the text ends at the right padding, the icon goes left of it
        LabelController l = SdkPopups.label(HqcUiIds.TOAST_LABEL_MODEL, 3, terminal, f.framework());
        l.setBounds(HqcUiIds.STATUS_PAD_PX, LABEL_Y, TEXT_RIGHT - HqcUiIds.STATUS_PAD_PX, LABEL_H);
        IconController id = SdkPopups.icon();
        IconController file = SdkPopups.icon();
        file.setModelID(HqcUiIds.TOAST_FILE_ICON_MODEL);

        PartialPopupController p = new PartialPopupController(HqcUiIds.POPUP_SDK_TOAST);
        SdkToastPopup s = new SdkToastPopup(l, id, file, p);
        Part want = wanted;
        s.place(want);
        s.icons(want, null); // no redraw: the popup is not shown yet, and showing it paints it
        SdkPopups.configure(p, f, terminal, HqcUiIds.POPUP_SDK_TOAST, HqcUiIds.TOAST_VISIBLE_MODEL,
                HqcUiIds.POPUP_TOAST_X, HqcUiIds.POPUP_TOAST_Y, w, h, HqcUiIds.POPUP_TOAST_SLOT,
                HqcUiIds.POPUP_TOAST_TYPE, HqcUiIds.POPUP_TOAST_PRIORITY, HqcUiIds.POPUP_TOAST_ZPM_SLOT);
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
            SdkPopups.colours(idIcon, want.ownColours);
            SdkPopups.colours(fileIcon, want.ownColours);
            changed = true;
        }
        return changed;
    }

    /** Asks the HMI to paint the dirty widgets now (the popup's stock triggerRepaint, as {@link SdkStatusPopup}). */
    void redraw() {
        SdkPopups.redraw(popup);
    }
}
