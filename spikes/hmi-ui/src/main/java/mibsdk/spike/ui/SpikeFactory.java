package mibsdk.spike.ui;

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

/** CarScreenFactory that logs the Car selection drawer (S1a) and appends 9 SDK ACTION rows to it. */
final class SpikeFactory extends CarScreenFactory {
    /** Row texts, served for TEXT_BASE + slot. Written by the probe thread, read on HMIEvent. */
    static volatile String[] texts = initialTexts();

    SpikeFactory(IFrameworkAccess fw) {
        super(fw);
    }

    private static String[] initialTexts() {
        String[] t = new String[SpikeIds.ROWS];
        t[0] = "SDK row 0 A [%1]";
        for (int i = 1; i < t.length; i++) {
            t[i] = "SDK row " + i;
        }
        return t;
    }

    public IDrawerController[] getSelectionDrawers(int terminal) {
        IDrawerController[] drawers = super.getSelectionDrawers(terminal);
        try {
            SpikeLog.log("getSelectionDrawers terminal=" + terminal + " count=" + drawers.length + " thread="
                    + Thread.currentThread().getName());
            if (drawers.length > 0 && drawers[0] instanceof DrawerController) {
                DrawerController d = (DrawerController) drawers[0];
                AbstractWidget main = (AbstractWidget) d.getDrawerMain();
                MenuItemController ref = logRows(d, main);
                if (ref != null) {
                    addRows(d, main, ref);
                }
            }
        } catch (Throwable t) {
            SpikeLog.log("getSelectionDrawers spike part FAILED: " + t);
        }
        return drawers;
    }

    /** S1a: every row's label text and event id. Returns the first row (template), or null. */
    private MenuItemController logRows(DrawerController d, AbstractWidget main) {
        SpikeLog.log("drawer id=" + d.getID() + " main=" + main.getClass().getName());
        List children = main.getChildren();
        MenuItemController first = null;
        for (int i = 0; i < children.size(); i++) {
            Object o = children.get(i);
            if (!(o instanceof MenuItemController)) {
                SpikeLog.log("  child " + i + " " + o.getClass().getName());
                continue;
            }
            MenuItemController m = (MenuItemController) o;
            if (first == null) {
                first = m;
            }
            int label = m.getLabelId();
            SpikeLog.log("  row " + i + " label=" + label + " text='" + getText(label) + "' event=" + m.getEvent()
                    + " widgetID=" + m.getWidgetID() + " model=" + m.getModelID() + " type=" + m.getType()
                    + " visible=" + m.isVisible());
        }
        int[] ids = d.getViewIDs();
        StringBuffer sb = new StringBuffer("  viewIDs:");
        for (int i = 0; ids != null && i < ids.length; i++) {
            sb.append(' ').append(ids[i]);
        }
        SpikeLog.log(sb.toString());
        return first;
    }

    private void addRows(DrawerController d, AbstractWidget main, MenuItemController ref) {
        MenuItemController[] rows = new MenuItemController[SpikeIds.ROWS];
        VisibilityProxyWidget[] vis = new VisibilityProxyWidget[SpikeIds.ROWS];
        for (int slot = 0; slot < SpikeIds.ROWS; slot++) {
            VisibilityProxyWidget v = new VisibilityProxyWidget();
            v.setModelID(SpikeIds.MODEL_BASE + slot);
            v.setBounds(0, 0, 100, 100);
            v.setDisableAvailable(false);
            MenuItemController m = new MenuItemController();
            int[] bm = ref.getBitmapIndices();
            if (bm != null) {
                m.setBitmaps(bm);
            }
            m.setModelID(SpikeIds.SELECTION_MODEL);
            m.setLabelId(SpikeIds.TEXT_BASE + slot);
            if (slot == 0) {
                m.setReplacementModelIds(new int[] {SpikeIds.LABEL_MODEL});
            }
            m.setGlassplateInsetsBottom(6);
            m.setGlassplateInsetsTop(7);
            m.setInternalID(SpikeIds.INTERNAL_ID_BASE + slot);
            m.setType(SpikeIds.DRAWER_ROW_TYPE_ACTION);
            m.setWidgetID(slot);
            m.add(v);
            rows[slot] = m;
            vis[slot] = v;
        }

        // Views first (sorted ids: findViews binary-searches); if this throws, the drawer is untouched.
        int[] ids = d.getViewIDs();
        int n = ids == null ? 0 : ids.length;
        int[] addIds = new int[SpikeIds.ROWS + 1];
        HMIView[][] addViews = new HMIView[SpikeIds.ROWS + 1][];
        for (int slot = 0; slot < SpikeIds.ROWS; slot++) {
            addIds[slot] = SpikeIds.MODEL_BASE + slot;
            addViews[slot] = new HMIView[] {vis[slot]};
        }
        addIds[SpikeIds.ROWS] = SpikeIds.SELECTION_MODEL;
        addViews[SpikeIds.ROWS] = rows;
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
        d.setViews(nids, nviews);
        // Row 0's %1 replacement: the builder appends its label to an existing replacement entry.
        int[] rids = d.getReplacementIDs();
        if (rids == null || rids.length == 0) {
            d.setReplacementWidgets(new int[] {SpikeIds.LABEL_MODEL}, new HMIView[][] {new HMIView[0]});
        } else {
            SpikeLog.log("drawer already has replacement ids; row 0 replacement not wired");
        }
        for (int slot = 0; slot < SpikeIds.ROWS; slot++) {
            ((AbstractWidgetController) main).add(rows[slot]);
        }
        SpikeLog.log("added " + SpikeIds.ROWS + " rows; views " + n + " -> " + nids.length);
    }

    public String getText(int id) {
        int slot = id - SpikeIds.TEXT_BASE;
        if (slot >= 0 && slot < SpikeIds.ROWS) {
            String[] t = texts;
            return t[slot] == null ? "" : t[slot];
        }
        return super.getText(id);
    }

    public String getAltText(int id) {
        int slot = id - SpikeIds.TEXT_BASE;
        if (slot >= 0 && slot < SpikeIds.ROWS) {
            return getText(id);
        }
        return super.getAltText(id);
    }
}
