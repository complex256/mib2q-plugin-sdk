package mibsdk.adapter.hqc.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.view.HMIView;
import de.audi.atip.hmi.view.IDrawerController;
import de.audi.tghu.car.hmi.evohigh.CarScreenFactory;
import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.evo.widgets.DrawerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.VisibilityProxyWidget;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import java.util.List;
import mibsdk.adapter.hqc.SdkGlobals;

/**
 * CarScreenFactory plus the SDK's pre-allocated rows at the end of the Car selection drawer: the Plugins row (slot
 * 0, visible) and {@link HqcUiIds#PLUGIN_ROW_SLOTS} plugin rows shown or hidden by their visibility models. Labels
 * come from {@link #getText} (live, from {@code SdkGlobals.uiSlots}). Every override runs on HMIEvent: it reads only
 * volatile SDK state, builds stock widgets, catches Throwable and falls back to the stock result.
 */
final class SdkCarScreenFactory extends CarScreenFactory {

    private final TextIds texts = new TextIds(HqcUiIds.TEXT_BASE);

    SdkCarScreenFactory(IFrameworkAccess fw) {
        super(fw);
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

    /** Appends the SDK rows once per drawer object; {@link #install} keeps the drawer whole if that fails. */
    private void addRows(DrawerController d) {
        int[] ids = d.getViewIDs();
        int n = ids == null ? 0 : ids.length;
        for (int k = 0; k < n; k++) {
            if (ids[k] == HqcUiIds.ROW_SELECTION_MODEL) {
                return; // already ours
            }
        }
        AbstractWidget main = (AbstractWidget) d.getDrawerMain();
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
            if (bitmaps != null) {
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
        install(d, (AbstractWidgetController) main, rows, nids, nviews);
    }

    /**
     * Adds the rows to the drawer's menu, then maps the views. If either step throws, the rows added so far are
     * removed again, so the drawer is never left half-built (views pointing at rows outside the menu, or rows
     * without their visibility views).
     */
    static void install(DrawerController d, AbstractWidgetController menu, MenuItemController[] rows, int[] ids,
                        HMIView[][] views) {
        int added = 0;
        try {
            for (; added < rows.length; added++) {
                menu.add(rows[added]);
            }
            d.setViews(ids, views);
        } catch (Throwable t) {
            for (int k = added - 1; k >= 0; k--) {
                try {
                    menu.remove(rows[k]);
                } catch (Throwable ignored) {
                    // keep removing the others
                }
            }
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

    public String getText(int id) {
        try {
            String s = texts.textFor(id, SdkGlobals.uiSlots);
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
            String s = texts.textFor(id, SdkGlobals.uiSlots);
            if (s != null) {
                return s;
            }
        } catch (Throwable t) {
            // stock text
        }
        return super.getAltText(id);
    }
}
