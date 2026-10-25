package mibsdk.spike.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.view.HMIView;
import de.audi.atip.hmi.view.IDrawerController;
import de.audi.atip.hmi.view.IPartialPopupController;
import de.audi.atip.hmi.view.Screen;
import de.audi.tghu.car.hmi.evohigh.CarScreenFactory;
import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.evo.widgets.DrawerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.PartialPopupStub;
import de.esolutions.hmi.widgets.audi.evo.widgets.VisibilityProxyWidget;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import java.util.List;

/**
 * CarScreenFactory that logs the Car selection drawer (S1a) and appends 9 SDK ACTION rows to it. Spike C: row 0 has
 * a custom icon (SDK ids served by getImagePath), row 1 a System stock icon, and the factory registers and builds
 * the SDK partial popup 609600.
 */
final class SpikeFactory extends CarScreenFactory {
    /** Texts, served for TEXT_BASE + index. Written by the probe thread, read on HMIEvent. */
    static volatile String[] texts = initialTexts();
    /** The one instance (spike B builds its pages with it). */
    static volatile SpikeFactory instance;
    /** Spike C I3: drawer row 0 (custom icon), for the "icon-next" command. Touched on HMIEvent only. */
    static volatile MenuItemController row0;
    /** Spike C: the stub registered for popup 609600 (the popup manager shows/queues by its slot). */
    static volatile PartialPopupStub popupStub;
    /** Spike C: image ids already logged by getImagePath (first call per id). */
    private final java.util.Hashtable loggedImages = new java.util.Hashtable();

    SpikeFactory(IFrameworkAccess fw) {
        super(fw);
        instance = this;
    }

    /** getFramework() is protected; SpikePage needs it for fonts and FwHMI. */
    IFrameworkAccess framework() {
        return getFramework();
    }

    private static String[] initialTexts() {
        String[] t = new String[SpikeIds.TEXTS];
        t[0] = "SDK page (showScreen)";
        t[1] = "SDK page (hijack)";
        t[2] = "SDK re-press test";
        for (int i = 3; i < SpikeIds.ROWS; i++) {
            t[i] = "SDK row " + i;
        }
        t[SpikeIds.TEXT_PAGE_TITLE - SpikeIds.TEXT_BASE] = "SDK spike page";
        t[SpikeIds.TEXT_PAGE_INFO - SpikeIds.TEXT_BASE] = "Info (text id)";
        // Spike D
        String[] opts = {"Off", "Low", "Mid", "High"};
        for (int i = 0; i < opts.length; i++) {
            t[SpikeIds.TEXT_COMBO_OPT0 - SpikeIds.TEXT_BASE + i] = opts[i];
        }
        t[SpikeIds.TEXT_ROW8 - SpikeIds.TEXT_BASE] = "Type-8 checkbox row";
        t[SpikeIds.TEXT_CONFIRM_TITLE - SpikeIds.TEXT_BASE] = "SDK confirm";
        t[SpikeIds.TEXT_BASS - SpikeIds.TEXT_BASE] = "Bass";
        t[SpikeIds.TEXT_SLIDER_TITLE - SpikeIds.TEXT_BASE] = "Bass (SDK slider)";
        t[SpikeIds.TEXT_CONFIRM_TEXT - SpikeIds.TEXT_BASE] =
                "Reset the plugin settings? This is the SDK confirm screen, a multi-line hint above two rows.";
        t[SpikeIds.TEXT_COMBO_LABEL - SpikeIds.TEXT_BASE] = "Combo level";
        t[SpikeIds.TEXT_SLIDER_MIN - SpikeIds.TEXT_BASE] = "-6";
        t[SpikeIds.TEXT_SLIDER_MAX - SpikeIds.TEXT_BASE] = "+6";
        t[SpikeIds.TEXT_SPELLER_TITLE - SpikeIds.TEXT_BASE] = "SDK speller";
        t[SpikeIds.TEXT_SPELLER_INFO - SpikeIds.TEXT_BASE] = "Name";
        t[SpikeIds.TEXT_SPELLER_HINT - SpikeIds.TEXT_BASE] = "Enter a name (SDK SpellerModel)";
        return t;
    }

    /** S2b: logs every Car screen creation; while a hijack is armed, the next one is the SDK page. */
    protected Screen createScreen(int id, int terminal) {
        try {
            SpikeLog.log("createScreen id=" + id + " terminal=" + terminal + " thread="
                    + Thread.currentThread().getName());
            Screen page = SpikePage.hijackScreen(this, id, terminal);
            if (page != null) {
                return page;
            }
        } catch (Throwable t) {
            SpikeLog.log("createScreen spike part FAILED: " + t);
        }
        return super.createScreen(id, terminal);
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
            if (slot == 0) {
                // Spike C I1: a Car-module SDK id pair, served from the spike icons dir by getImagePath.
                m.setBitmaps(new int[] {SpikeIds.ICON_PLUG, SpikeIds.ICON_PLUG_REFLEX});
                row0 = m;
            } else if (slot == 1) {
                // Spike C I2: a System-module stock pair (phone) on a Car drawer row.
                m.setBitmaps(new int[] {SpikeIds.SYS_ICON_PHONE, SpikeIds.SYS_ICON_PHONE_REFLEX});
            } else if (bm != null && slot != SpikeIds.ROW_NO_ICON) {
                // S1f: row 2 gets no bitmaps; does a row without an icon render and take focus?
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

    // ---- Spike C: custom images, SDK partial popup ---------------------------------------------------------------

    /** I1/I3: SDK image ids map to PNGs in the spike icons dir, relative to ImageRoot (lsd/images). */
    public String getImagePath(int id, int terminal) {
        try {
            if (id >= SpikeIds.ICON_FIRST && id <= SpikeIds.ICON_LAST) {
                String p = SpikeIds.ICON_REL_DIR + id + ".png";
                Integer key = new Integer(id);
                if (loggedImages.put(key, key) == null) {
                    SpikeLog.log("getImagePath " + id + " -> " + p + " thread=" + Thread.currentThread().getName());
                }
                return p;
            }
        } catch (Throwable t) {
            SpikeLog.log("getImagePath FAILED: " + t);
        }
        return super.getImagePath(id, terminal);
    }

    /** S1: the stock stubs plus one stub for popup 609600 (popup 65's parameters, own visibility model). */
    public IPartialPopupController[] getPartialPopupStubs(int terminal) {
        IPartialPopupController[] stock = super.getPartialPopupStubs(terminal);
        try {
            int n = stock == null ? 0 : stock.length;
            IPartialPopupController[] all = new IPartialPopupController[n + 1];
            if (n > 0) {
                System.arraycopy(stock, 0, all, 0, n);
            }
            PartialPopupStub stub = new PartialPopupStub(SpikeIds.POPUP_SDK, SpikeIds.POPUP_VISIBLE_MODEL, -1, 250,
                    SpikeProbes.popupSlot,
                    12, true, 7, terminal, 2, null, true, true);
            all[n] = stub;
            popupStub = stub;
            SpikeLog.log("getPartialPopupStubs terminal=" + terminal + " stock=" + n + " + " + SpikeIds.POPUP_SDK
                    + " thread=" + Thread.currentThread().getName());
            return all;
        } catch (Throwable t) {
            SpikeLog.log("getPartialPopupStubs spike part FAILED: " + t);
            return stock;
        }
    }

    public IPartialPopupController getPartialPopup(int id, int terminal) {
        if (id == SpikeIds.POPUP_SDK) {
            try {
                IPartialPopupController p = SpikePopup.build(this, terminal);
                SpikeLog.log("getPartialPopup " + id + " built thread=" + Thread.currentThread().getName());
                return p;
            } catch (Throwable t) {
                SpikeLog.log("getPartialPopup " + id + " FAILED: " + t);
                return null;
            }
        }
        return super.getPartialPopup(id, terminal);
    }

    public String getText(int id) {
        int slot = id - SpikeIds.TEXT_BASE;
        if (slot >= 0 && slot < SpikeIds.TEXTS) {
            String[] t = texts;
            return t[slot] == null ? "" : t[slot];
        }
        return super.getText(id);
    }

    public String getAltText(int id) {
        int slot = id - SpikeIds.TEXT_BASE;
        if (slot >= 0 && slot < SpikeIds.TEXTS) {
            return getText(id);
        }
        return super.getAltText(id);
    }
}
