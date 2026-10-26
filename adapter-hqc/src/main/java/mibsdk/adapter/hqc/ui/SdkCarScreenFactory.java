package mibsdk.adapter.hqc.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.view.HMIView;
import de.audi.atip.hmi.view.IDrawerController;
import de.audi.atip.hmi.view.IPartialPopupController;
import de.audi.tghu.car.hmi.evohigh.CarScreenFactory;
import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.evo.widgets.DrawerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.PartialPopupStub;
import de.esolutions.hmi.widgets.audi.evo.widgets.VisibilityProxyWidget;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import java.util.List;
import mibsdk.adapter.hqc.SdkGlobals;

/**
 * CarScreenFactory plus the SDK's pre-allocated rows at the end of the Car selection drawer: the Plugins row (slot
 * 0, visible) and {@link HqcUiIds#PLUGIN_ROW_SLOTS} plugin rows shown or hidden by their visibility models. Labels
 * come from {@link #getText} (live, from {@code SdkGlobals.uiSlots}), and so does the SDK page title
 * ({@link HqcUiIds#TEXT_PAGE_TITLE}) and the input texts ({@link InputTextIds}). It also registers and builds the SDK status popup
 * {@link HqcUiIds#POPUP_SDK_STATUS} ({@link SdkStatusPopup}), recording how that went in
 * {@code SdkGlobals.statusPopup}.
 * Every override runs on a stock thread (HMIEvent; the popup stubs on the startup dispatcher): it reads only volatile
 * SDK state, builds stock widgets, catches Throwable and falls back to the stock result.
 */
final class SdkCarScreenFactory extends CarScreenFactory {

    /** The Car factory SDK pages are built with (their ref widgets, fonts and title text); null until built. */
    static volatile SdkCarScreenFactory current;

    /** The SDK drawer rows by slot, once a drawer took them ({@link HmiWidgets} sets their bitmaps); else null. */
    static volatile MenuItemController[] sdkRows;

    /** {@link #getImagePath} logs the first serve of each SDK id, at most {@link #MAX_SERVE_LOGS} a session. */
    private static final int MAX_SERVE_LOGS = 64;
    private static final boolean[] SERVED = new boolean[HqcUiIds.ICON_ID_LAST - HqcUiIds.ICON_ID_FIRST + 1];
    private static int serveLogs; // HMIEvent only

    private final TextIds texts = new TextIds(HqcUiIds.TEXT_BASE);

    /** Orders the {@code SdkGlobals.statusPopup} moves (stock threads only; a few instructions under it). */
    private static final Object POPUP_STATE_LOCK = new Object();

    SdkCarScreenFactory(IFrameworkAccess fw) {
        super(fw);
        current = this;
    }

    /** getFramework() is protected; the page recipe needs it for fonts. */
    IFrameworkAccess framework() {
        return getFramework();
    }

    public IDrawerController[] getSelectionDrawers(int terminal) {
        IDrawerController[] drawers = super.getSelectionDrawers(terminal);
        try {
            for (int i = 0; drawers != null && i < drawers.length; i++) {
                if (drawers[i] instanceof DrawerController
                        && ((DrawerController) drawers[i]).getID() == HqcUiIds.DRAWER_ID) {
                    addRows((DrawerController) drawers[i]);
                }
            }
        } catch (Throwable t) {
            // stock drawer (addRows changes nothing until its views are in place)
        }
        return drawers;
    }

    /**
     * Appends the SDK rows once per drawer object; {@link #install} keeps the drawer whole if that fails. Each row
     * takes its icon's bitmaps from {@code SdkGlobals.uiSlots} as it is now; later changes come from
     * {@link HmiWidgets} through {@link #sdkRows}.
     */
    void addRows(DrawerController d) {
        int[] ids = d.getViewIDs();
        int n = ids == null ? 0 : ids.length;
        for (int k = 0; k < n; k++) {
            if (ids[k] == HqcUiIds.ROW_SELECTION_MODEL) {
                return; // already ours
            }
        }
        AbstractWidget main = (AbstractWidget) d.getDrawerMain();
        DrawerAssignment slots = SdkGlobals.uiSlots;
        int[] bitmaps = sourceBitmaps(main.getChildren());
        MenuItemController[] rows = new MenuItemController[HqcUiIds.ROW_SLOTS];
        int[] addIds = new int[HqcUiIds.ROW_SLOTS + 1];
        HMIView[][] addViews = new HMIView[HqcUiIds.ROW_SLOTS + 1][];
        for (int slot = 0; slot < HqcUiIds.ROW_SLOTS; slot++) {
            VisibilityProxyWidget v = new VisibilityProxyWidget();
            v.setModelID(HqcUiIds.ROW_VISIBILITY_MODEL_0 + slot);
            v.setBounds(0, 0, 100, 100);
            v.setDisableAvailable(false);
            MenuItemController m = new MenuItemController();
            int[] icon = slots.iconAt(slot);
            if (icon != null) {
                m.setBitmaps(icon);
            } else if (bitmaps != null) {
                m.setBitmaps((int[]) bitmaps.clone());
            }
            // No event and no key handler: MenuItemBuilder picks ChoiceModelKeyHandler inside CloseDrawerKeyHandler.
            m.setModelID(HqcUiIds.ROW_SELECTION_MODEL);
            m.setLabelId(texts.idFor(slot));
            m.setGlassplateInsetsBottom(HqcUiIds.ROW_GLASSPLATE_INSET_BOTTOM);
            m.setGlassplateInsetsTop(HqcUiIds.ROW_GLASSPLATE_INSET_TOP);
            m.setInternalID(HqcUiIds.ROW_INTERNAL_ID_BASE + slot);
            m.setType(HqcUiIds.DRAWER_ROW_TYPE_ACTION);
            m.setWidgetID(slot);
            m.add(v);
            rows[slot] = m;
            addIds[slot] = HqcUiIds.ROW_VISIBILITY_MODEL_0 + slot;
            addViews[slot] = new HMIView[] {v};
        }
        addIds[HqcUiIds.ROW_SLOTS] = HqcUiIds.ROW_SELECTION_MODEL;
        addViews[HqcUiIds.ROW_SLOTS] = rows;

        // Merge into the drawer's views, ids sorted (findViews binary-searches them).
        int[] nids = new int[n + addIds.length];
        HMIView[][] nviews = new HMIView[nids.length][];
        int a = 0;
        int o = 0;
        for (int k = 0; k < n; k++) {
            while (a < addIds.length && addIds[a] < ids[k]) {
                nids[o] = addIds[a];
                nviews[o++] = addViews[a++];
            }
            nids[o] = ids[k];
            nviews[o++] = d.findViews(ids[k]);
        }
        while (a < addIds.length) {
            nids[o] = addIds[a];
            nviews[o++] = addViews[a++];
        }
        if (install(d, (AbstractWidgetController) main, rows, nids, nviews)) {
            sdkRows = rows;
        }
    }

    /**
     * Adds the rows to the drawer's menu, then maps the views. If either step throws, the rows added so far are
     * removed again, so the drawer is never left half-built (views pointing at rows outside the menu, or rows
     * without their visibility views). True if the rows went in.
     */
    static boolean install(DrawerController d, AbstractWidgetController menu, MenuItemController[] rows, int[] ids,
                        HMIView[][] views) {
        int added = 0;
        try {
            for (; added < rows.length; added++) {
                menu.add(rows[added]);
            }
            d.setViews(ids, views);
            return true;
        } catch (Throwable t) {
            for (int k = added - 1; k >= 0; k--) {
                try {
                    menu.remove(rows[k]);
                } catch (Throwable ignored) {
                    // keep removing the others
                }
            }
            return false;
        }
    }

    /** The bitmaps of drawer child {@link HqcUiIds#ROW_BITMAP_SOURCE}, or null (none, or no such row). */
    private static int[] sourceBitmaps(List children) {
        int src = HqcUiIds.ROW_BITMAP_SOURCE;
        if (src < 0 || children == null || src >= children.size()
                || !(children.get(src) instanceof MenuItemController)) {
            return null;
        }
        return ((MenuItemController) children.get(src)).getBitmapIndices();
    }

    /**
     * SDK icon ids ({@link HqcUiIds#ICON_ID_FIRST}..{@link HqcUiIds#ICON_ID_LAST}) with a known path: that path,
     * relative to ImageRoot, precomputed when the id was given (a lookup, no file work). Anything else: stock.
     */
    public String getImagePath(int id, int terminal) {
        try {
            if (id >= HqcUiIds.ICON_ID_FIRST && id <= HqcUiIds.ICON_ID_LAST) {
                String p = SdkGlobals.ICON_IDS.relPathFor(id);
                if (p != null) {
                    int i = id - HqcUiIds.ICON_ID_FIRST;
                    if (!SERVED[i] && serveLogs < MAX_SERVE_LOGS) {
                        SERVED[i] = true;
                        serveLogs++;
                        SdkGlobals.LOG.append("INFO", "mibsdk", "getImagePath " + id + " -> " + p);
                    }
                    return p;
                }
            }
        } catch (Throwable t) {
            // stock path
        }
        return super.getImagePath(id, terminal);
    }

    /**
     * The stock stubs plus the SDK status popup's ({@code SdkGlobals.statusPopup} := 1), unless the popup already
     * failed this boot (-1, sticky: no stub). On any Throwable: the stock stubs, and -1.
     */
    public IPartialPopupController[] getPartialPopupStubs(int terminal) {
        IPartialPopupController[] stock = super.getPartialPopupStubs(terminal);
        try {
            if (SdkGlobals.statusPopup == -1) {
                return stock;
            }
            int n = stock == null ? 0 : stock.length;
            IPartialPopupController[] all = new IPartialPopupController[n + 1];
            if (n > 0) {
                System.arraycopy(stock, 0, all, 0, n);
            }
            all[n] = new PartialPopupStub(HqcUiIds.POPUP_SDK_STATUS, HqcUiIds.STATUS_POPUP_VISIBLE_MODEL,
                    HqcUiIds.POPUP_SDK_STUB_NO_MODEL, HqcUiIds.POPUP_SDK_PRIORITY, HqcUiIds.POPUP_SDK_SLOT,
                    HqcUiIds.POPUP_SDK_ZPM_SLOT, true, HqcUiIds.POPUP_SDK_TYPE, terminal, HqcUiIds.POPUP_SDK_STUB_KIND,
                    null, true, true);
            popupState(1, "registered");
            return all;
        } catch (Throwable t) {
            popupState(-1, "failed reason=stub " + t);
            return stock;
        }
    }

    /**
     * Builds the SDK status popup ({@code SdkGlobals.statusPopup} := 2); on any Throwable null, and -1. Once the popup
     * failed this boot, null without a build. Other ids: stock.
     */
    public IPartialPopupController getPartialPopup(int id, int terminal) {
        if (id == HqcUiIds.POPUP_SDK_STATUS) {
            try {
                if (SdkGlobals.statusPopup == -1) {
                    return null;
                }
                IPartialPopupController p = SdkStatusPopup.build(this, terminal);
                popupState(2, "built");
                return p;
            } catch (Throwable t) {
                popupState(-1, "failed reason=build " + t);
                return null;
            }
        }
        return super.getPartialPopup(id, terminal);
    }

    /**
     * Moves {@code SdkGlobals.statusPopup} to {@code state}, never away from -1 (sticky) and never from 2 back to 1;
     * logs each move once. Never throws.
     */
    private static void popupState(int state, String what) {
        boolean moved;
        synchronized (POPUP_STATE_LOCK) {
            int now = SdkGlobals.statusPopup;
            moved = now != -1 && now != state && !(now == 2 && state == 1);
            if (moved) {
                SdkGlobals.statusPopup = state;
            }
        }
        if (moved) {
            try {
                SdkGlobals.LOG.append(state == -1 ? "ERROR" : "STATUS", "mibsdk", "ui status popup "
                        + HqcUiIds.POPUP_SDK_STATUS + " " + what);
            } catch (Throwable ignored) {
                // logging never breaks the HMI
            }
        }
    }

    public String getText(int id) {
        try {
            String s = sdkText(id);
            if (s != null) {
                return s;
            }
        } catch (Throwable t) {
            // stock text
        }
        return super.getText(id);
    }

    public String getAltText(int id) {
        try {
            String s = sdkText(id);
            if (s != null) {
                return s;
            }
        } catch (Throwable t) {
            // stock text
        }
        return super.getAltText(id);
    }

    /**
     * The SDK's text for {@code id}, or null for a stock id: the input texts first ({@link InputTextIds}: typed rows,
     * the slider screen, dialogs), then the page title and the drawer rows' labels. Reads volatile state only.
     */
    private String sdkText(int id) {
        String s = InputTextIds.textFor(id, PageScreens.shownNow(), PageScreens.sliderNow(), PageScreens.dialogNow());
        if (s != null) {
            return s;
        }
        return id == HqcUiIds.TEXT_PAGE_TITLE ? PageScreens.title() : texts.textFor(id, SdkGlobals.uiSlots);
    }
}
