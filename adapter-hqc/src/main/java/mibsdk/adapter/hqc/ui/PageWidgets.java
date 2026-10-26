package mibsdk.adapter.hqc.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.event.JoystickEvent;
import de.audi.atip.hmi.event.KeyEvent;
import de.audi.atip.hmi.event.WheelButtonEvent;
import de.audi.atip.hmi.model.IntegerListCell;
import de.audi.atip.hmi.model.ListCell;
import de.audi.atip.hmi.model.ListModel;
import de.audi.atip.hmi.model.TextListCell;
import de.audi.atip.hmi.view.HMIView;
import de.audi.atip.hmi.view.IScreenData;
import de.audi.atip.hmi.view.IScreenManager;
import de.audi.atip.hmi.view.Screen;
import de.audi.atip.hmi.view.ScreenData;
import de.audi.tghu.car.hmi.evohigh.FontFactory;
import de.audi.tghu.fwhmi.FwHMI;
import de.esolutions.hmi.widgets.audi.base.InitializationContext;
import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.evo.IWidgetKeyHandler;
import de.esolutions.hmi.widgets.audi.evo.ScreenWidgetEVO;
import de.esolutions.hmi.widgets.audi.evo.gridlayout.AxisConstraints;
import de.esolutions.hmi.widgets.audi.evo.gridlayout.GridLayout;
import de.esolutions.hmi.widgets.audi.evo.gridlayout.GridLayoutHints;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.CheckboxRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.CompositeRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.ContainerRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.FocusCursorRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.GlassplateRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.IconRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.LabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.MenuItemRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.ProgressBarRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.RadioButtonRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.ScreenRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.ScrollbarRendererKanziHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.TitleBarRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.menu.MenuRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.CheckboxController;
import de.esolutions.hmi.widgets.audi.evo.widgets.ContainerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.FocusCursorController;
import de.esolutions.hmi.widgets.audi.evo.widgets.GlassplateController;
import de.esolutions.hmi.widgets.audi.evo.widgets.IconController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.ProgressBarController;
import de.esolutions.hmi.widgets.audi.evo.widgets.RadioButtonController;
import de.esolutions.hmi.widgets.audi.evo.widgets.ScrollbarController;
import de.esolutions.hmi.widgets.audi.evo.widgets.StatusBarStubController;
import de.esolutions.hmi.widgets.audi.evo.widgets.TitleBarWidget;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.InfoLineRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.ListController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemColumnsConstraints;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.list.ListItemFactory;
import mibsdk.platform.UiEntry;

/**
 * The stock side of SDK pages: spike B's page recipe (research note §8, modelled on
 * {@code EngineeringScreenBag2.engModeApplConfigBundles}) and the screen manager calls. Runs on HMIEvent only, inside
 * {@link PageScreens}' guarded calls; every widget callback catches Throwable and falls back to stock behaviour.
 *
 * <p>A page is a {@code ScreenWidgetEVO} with a body menu (glassplate, focus cursor, scrollbar) holding one
 * {@code ListController} on {@link HqcUiIds#PAGE_LIST_MODEL}, and a head title bar whose title is factory text
 * {@link HqcUiIds#TEXT_PAGE_TITLE}. Every row is a list item with a label cell (column 0) and, by row kind (the
 * list's record-set column), a checkbox, radio, progress, value-text or item-icon cell ({@link Rows}, spike D's
 * recipes, research note 2026-10-25 §7.2). The layout's typed rows follow the list in the menu, in spec order: a
 * type-64 combo row on its slot's pooled ChoiceModel, or a type-128 rotary row on its slot's pooled RangeModel whose
 * own key handler ({@link SliderKeys}) opens the slider screen ({@link SliderScreen}). Layout numbers (bounds,
 * palettes, fonts) are the stock bag's decoded values, as spike B used them; the cells' and typed rows' literals are
 * in {@link HqcUiIds}.
 */
final class PageWidgets implements PageScreens.Screens {

    private static final int TERMINAL = 0;

    private final Object frameworkAccess;

    /** {@code frameworkAccess}: the {@code de.audi.atip.base.IFrameworkAccess} service. */
    PageWidgets(Object frameworkAccess) {
        this.frameworkAccess = frameworkAccess;
    }

    private FwHMI fw() {
        return (FwHMI) ((IFrameworkAccess) frameworkAccess).getHmiServiceApp();
    }

    private IScreenManager screens() {
        return fw().getScreenManager(TERMINAL);
    }

    public int currentId() {
        IScreenManager sm = screens();
        return sm == null ? -1 : sm.getCurrentConnectedScreenId();
    }

    public boolean currentBlocked() {
        IScreenManager sm = screens();
        IScreenData d = sm == null ? null : sm.getCurrentConnectedScreenData();
        if (d == null) {
            return false;
        }
        if (d.isPopup()) {
            return true;
        }
        int[] partial = d.getPartialPopups();
        for (int i = 0; partial != null && i < partial.length; i++) {
            if (PageScreens.isParkingOverlay(partial[i])) {
                return true;
            }
        }
        return false;
    }

    public int pendingId() {
        IScreenManager sm = screens();
        return sm != null && sm.isScreenChangePending() ? sm.getPendingScreenId() : -1;
    }

    public Object currentData() {
        IScreenManager sm = screens();
        return sm == null ? null : sm.getCurrentConnectedScreenData();
    }

    public boolean isCurrent(Object screen) {
        IScreenManager sm = screens();
        return screen != null && sm != null && sm.getCurrentConnectedScreen() == screen;
    }

    public Object build(String pageKey, String title, PageScreens owner, PageLayout layout) {
        return build(factory(), TERMINAL, owner, pageKey, layout);
    }

    public Object buildSlider(int slot, PageLayout layout, PageScreens owner) {
        return SliderScreen.build(factory(), TERMINAL, slot, layout, owner);
    }

    private static SdkCarScreenFactory factory() {
        SdkCarScreenFactory f = SdkCarScreenFactory.current;
        if (f == null) {
            throw new IllegalStateException("no SDK Car screen factory");
        }
        return f;
    }

    /**
     * Shows a prebuilt SDK screen (the page or a sub-screen, under its own id): it needs its terminal set (the cache
     * path that does it is bypassed) and the current screen's partial popups, colour scheme and screen mode (spike B,
     * S2a).
     */
    public void show(Object screen) {
        FwHMI fw = fw();
        IScreenData cur = fw.getScreenManager(TERMINAL).getCurrentConnectedScreenData();
        Screen page = (Screen) screen;
        page.setTerminal(fw.getHMITerminal(TERMINAL));
        int[] popups = cur == null ? null : copy(cur.getPartialPopups());
        int scheme = cur == null ? 0 : cur.getColorScheme();
        int mode = cur == null ? 0 : cur.getScreenMode();
        fw.showScreen(TERMINAL, 0, new ScreenData(page.getID(), false, false, null, false, page, popups,
                null, -1L, -1L, 0, scheme, mode, null));
    }

    /**
     * BACK out of SDK pages: re-shows a copy of the saved stock screen data (spike B, BACK mode "reshow"), with the
     * partial popups on screen now, so a popup that came up meanwhile (OPS/PDC) is never hidden.
     */
    public void showStock(Object data) {
        if (!(data instanceof ScreenData)) {
            throw new IllegalStateException("no stock screen data: " + data);
        }
        FwHMI fw = fw();
        fw.showScreen(TERMINAL, 0, returnData((ScreenData) data, fw.getScreenManager(TERMINAL)
                .getCurrentConnectedScreenData()));
    }

    /**
     * Fallback when no stock screen was saved: shows {@code id} with no prebuilt screen (the screen manager builds it
     * through its module's factory), with the current partial popups.
     */
    public void showCarScreen(int id) {
        FwHMI fw = fw();
        IScreenData cur = fw.getScreenManager(TERMINAL).getCurrentConnectedScreenData();
        int[] popups = cur == null ? null : copy(cur.getPartialPopups());
        int scheme = cur == null ? 0 : cur.getColorScheme();
        int mode = cur == null ? 0 : cur.getScreenMode();
        fw.showScreen(TERMINAL, 0, new ScreenData(id, false, false, null, false, null, popups, null, -1L, -1L, 0,
                scheme, mode, null));
    }

    /** A copy of {@code saved} carrying a copy of {@code current}'s partial popups (none without a current screen). */
    static ScreenData returnData(ScreenData saved, IScreenData current) {
        ScreenData d = new ScreenData(saved);
        d.setPartialPopups(current == null ? null : copy(current.getPartialPopups()));
        return d;
    }

    static int[] copy(int[] a) {
        return a == null ? null : (int[]) a.clone();
    }

    /** Rewrites the rows in place (so focus stays), then trims or extends the list. */
    public void writeRows(PageRow[] rows) {
        ListModel m = SdkCarModelBank.pageList();
        if (m == null) {
            throw new IllegalStateException("no page list model");
        }
        int had = m.getLength();
        for (int i = 0; i < rows.length; i++) {
            ListCell[] row = cells(rows[i]);
            if (i < had) {
                m.setRow(i, row);
            } else {
                m.addRow(row);
            }
        }
        for (int i = had - 1; i >= rows.length; i--) {
            m.removeRow(i);
        }
    }

    /** One model row: label, value, kind, enabled 1/0, value text ("" for none). */
    static ListCell[] cells(PageRow r) {
        ListCell[] c = new ListCell[HqcUiIds.PAGE_COLUMNS];
        c[HqcUiIds.PAGE_COL_LABEL] = TextListCell.create(r.label == null ? "" : r.label);
        c[HqcUiIds.PAGE_COL_VALUE] = IntegerListCell.create(r.value);
        c[HqcUiIds.PAGE_COL_KIND] = IntegerListCell.create(r.kind);
        c[HqcUiIds.PAGE_COL_ENABLED] = IntegerListCell.create(r.enabled ? 1 : 0);
        c[HqcUiIds.PAGE_COL_VALUE_TEXT] = TextListCell.create(r.valueText == null ? "" : r.valueText);
        return c;
    }

    // ---- the page -----------------------------------------------------------------------------------------------

    /** The page screen: tells its owner when it connects and when it leaves. */
    static final class PageScreen extends ScreenWidgetEVO {
        private final PageScreens owner;
        private final String pageKey;
        private final PageLayout layout;

        PageScreen(int id, PageScreens owner, String pageKey, PageLayout layout) {
            super(id);
            this.owner = owner;
            this.pageKey = pageKey;
            this.layout = layout;
        }

        public void connected(InitializationContext c) {
            super.connected(c);
            owner.screenConnected(this, pageKey, layout); // never throws
        }

        public void disconnecting() {
            try {
                super.disconnecting();
            } finally {
                owner.screenDisconnecting(this); // never throws
            }
        }
    }

    /** The page's menu: BACK (press and release) is the SDK's; every other key is stock. */
    static final class PageMenu extends MenuController {
        private final PageScreens owner;

        PageMenu(PageScreens owner) {
            this.owner = owner;
        }

        public void keyPressed(KeyEvent e) {
            try {
                if (e.getKeyCode() == HqcUiIds.KEY_BACK) {
                    e.consume(true);
                    owner.backPressed(); // never throws
                    return;
                }
            } catch (Throwable t) {
                // stock handling below
            }
            super.keyPressed(e);
        }

        public void keyReleased(KeyEvent e) {
            try {
                if (e.getKeyCode() == HqcUiIds.KEY_BACK) {
                    e.consume(true);
                    return;
                }
            } catch (Throwable t) {
                // stock handling below
            }
            super.keyReleased(e);
        }
    }

    static Screen build(SdkCarScreenFactory f, int t, PageScreens owner, String pageKey, PageLayout layout) {
        AbstractWidgetController ref0 = f.getRefWidget(t, 0, f);

        GlassplateController glass = new GlassplateController();
        glass.setRenderer(new GlassplateRendererHigh(glass));
        glass.setBounds(0, 0, 100, 100);
        glass.setSdsNumbersGapWidth(4);
        glass.setSdsNumbersWidth(64);
        FocusCursorController cursor = new FocusCursorController();
        cursor.setRenderer(new FocusCursorRendererHigh(cursor));
        cursor.setBounds(0, 0, 100, 100);
        cursor.setColorIndices(new int[] {1, 2, 4, 0});
        cursor.setOptionsIconVisible(false);
        ScrollbarController scroll = new ScrollbarController();
        scroll.setRenderer(new ScrollbarRendererKanziHigh(scroll));
        scroll.setBounds(933, 8, 5, 308);

        ListController list = list(layout);

        PageMenu menu = new PageMenu(owner);
        menu.setRenderer(new MenuRendererHigh(menu));
        menu.createInfolineWidgets(new InfoLineRendererHigh());
        menu.getInfoline().getInfolineRenderer().setFonts(FontFactory.getFonts(2, t, f.framework()));
        menu.getInfolineTimer().setIdleTime(800);
        menu.getInfoline().setColorIndices(new int[] {2, 0, 4, 3});
        menu.getLayout().setBackgroundInsetsVert(7);
        menu.getLayout().setContentLeftOffset(12);
        menu.getLayout().setContentRightOffset(32);
        menu.getLayout().setContentRightOffsetSmallStage(25);
        menu.getLayout().setItemsGap(9);
        menu.getLayout().setOptionsIconAdditionalRightInsets(26);
        menu.setBounds(59, 77, 946, 324);
        menu.setColorIndices(new int[] {1, 0, 4, 0, 2, 3});
        menu.add(glass, 4);
        menu.add(cursor, 1);
        menu.add(scroll, 3);
        menu.add(list);

        PageLayout.TypedRow[] typed = layout == null ? new PageLayout.TypedRow[0] : layout.typed();
        int[] ids = new int[1 + typed.length];
        HMIView[][] views = new HMIView[ids.length][];
        ids[0] = HqcUiIds.PAGE_LIST_MODEL;
        views[0] = new HMIView[] {list};
        for (int s = 0; s < typed.length; s++) {
            MenuItemController row = typedRow(typed[s], s, f, t);
            menu.add(row);
            ids[1 + s] = row.getModelID();
            views[1 + s] = new HMIView[] {row};
        }
        ScreenWidgetEVO s = new PageScreen(HqcUiIds.PAGE_SCREEN_ID, owner, pageKey, layout);
        return shell(s, f, t, ref0, menu, HqcUiIds.TEXT_PAGE_TITLE, 3, ids, views);
    }

    /**
     * Typed slot {@code slot}'s row (§7.2): a dropdown is a type-64 combo row on the slot's pooled ChoiceModel, its
     * options' text ids as {@code setTextIds}; a slider is a type-128 rotary row on the slot's pooled RangeModel with
     * its own key handler (set before connect). Both carry the slot's label text id and internal id.
     */
    static MenuItemController typedRow(PageLayout.TypedRow r, int slot, SdkCarScreenFactory f, int t) {
        MenuItemController row = new MenuItemController();
        int label = HqcUiIds.TEXT_INPUT_BASE + HqcUiIds.TEXT_TYPED_STRIDE * slot;
        if (r.kind == UiEntry.DROPDOWN) {
            CompositeRendererHigh cr = new CompositeRendererHigh(row);
            row.setRenderer(cr);
            row.setBitmaps(HqcUiIds.COMBO_BITMAPS);
            cr.setFonts(FontFactory.getFonts(HqcUiIds.COMBO_FONTS, t, f.framework()));
            row.setModelID(HqcUiIds.TYPED_CHOICE_MODEL_0 + slot);
            int[] options = new int[r.optionCount()];
            for (int i = 0; i < options.length; i++) {
                options[i] = label + 1 + i;
            }
            row.setTextIds(options);
            row.setLabelId(label);
            row.setGlassplateInsetsBottom(HqcUiIds.TYPED_GLASSPLATE_INSET_BOTTOM);
            row.setGlassplateInsetsTop(HqcUiIds.TYPED_GLASSPLATE_INSET_TOP);
            row.setInternalID(HqcUiIds.TYPED_INTERNAL_ID_0 + slot);
            row.setType(HqcUiIds.ROW_TYPE_COMBO);
            row.setWidgetID(HqcUiIds.COMBO_WIDGET_ID);
        } else {
            row.setRenderer(new CompositeRendererHigh(row));
            row.setModelID(HqcUiIds.TYPED_RANGE_MODEL_0 + slot);
            row.setLabelId(label);
            row.setGlassplateInsetsBottom(HqcUiIds.TYPED_GLASSPLATE_INSET_BOTTOM);
            row.setGlassplateInsetsTop(HqcUiIds.TYPED_GLASSPLATE_INSET_TOP);
            row.setHasInfolineDisclaimer(false);
            row.setInternalID(HqcUiIds.TYPED_INTERNAL_ID_0 + slot);
            row.setType(HqcUiIds.ROW_TYPE_ROTARY);
            row.setKeyHandler(new SliderKeys(slot));
        }
        return row;
    }

    /** A slider row's key handler: SELECT (key 17) opens the slider screen (no SMM event); nothing else. */
    static final class SliderKeys implements IWidgetKeyHandler {
        final int slot;

        SliderKeys(int slot) {
            this.slot = slot;
        }

        public void keyPressed(AbstractWidgetController w, KeyEvent e) {
            try {
                if (e != null && e.getKeyCode() == HqcUiIds.KEY_ENTER) {
                    e.consume(false);
                    PageScreens.sliderPressed(slot); // never throws
                }
            } catch (Throwable t) {
                // never propagate into the HMI
            }
        }

        public void keyReleased(AbstractWidgetController w, KeyEvent e) {
        }

        public void keyTurned(AbstractWidgetController w, WheelButtonEvent e) {
        }

        public void keyMoved(AbstractWidgetController w, JoystickEvent e) {
        }
    }

    /**
     * The screen {@code s} around {@code bodyChild} (spike B's page shell): the body container (layer 1), the title bar
     * with factory text {@code titleText} (layer 2), the status-bar stub, the ref widgets and the palettes. Its views:
     * the stub's (138) and {@code ids}/{@code views}, sorted by id (findViews binary-searches them).
     */
    static ScreenWidgetEVO shell(ScreenWidgetEVO s, SdkCarScreenFactory f, int t, AbstractWidgetController ref0,
                                 AbstractWidgetController bodyChild, int titleText, int smallStage, int[] ids,
                                 HMIView[][] views) {
        ContainerController body = new ContainerController();
        body.setRenderer(new ContainerRendererHigh(body));
        body.setBounds(0, 0, 0, 0);
        body.setEntertainmentMenuTransformation(12.0f, 12.0f, 0.96f, 0.96f, 0.5f);
        body.setOpacitySet(1.0f, 0.4f);
        body.setOptionMenuTransformation(-4.0f, 7.0f, 0.6f, 0.6f, 0.5f);
        body.setSelectionMenuTransformation(391.0f, 7.0f, 0.6f, 0.6f, 0.25f);
        body.add(bodyChild);

        IconController icon = new IconController();
        icon.setRenderer(new IconRendererHigh(icon));
        icon.setBitmaps(new int[] {HqcUiIds.PAGE_TITLE_ICON_BITMAP});
        icon.setBounds(0, 0, 100, 100);
        LabelController title = new LabelController();
        LabelRendererHigh titleR = new LabelRendererHigh(title);
        title.setRenderer(titleR);
        titleR.setFonts(FontFactory.getFonts(4, t, f.framework()));
        title.setTextIds(new int[] {titleText});
        title.setBounds(0, 0, 0, 30);
        title.setColorIndices(new int[] {1, 2, 4, 0});
        TitleBarWidget bar = new TitleBarWidget();
        bar.setRenderer(new TitleBarRendererHigh(bar));
        bar.setBitmaps(new int[] {HqcUiIds.PAGE_TITLE_BAR_BITMAP, HqcUiIds.PAGE_TITLE_BAR_BITMAP});
        bar.getTitleLayout().setGapInsideBreadcrumbs(15);
        bar.getTitleLayout().setGapInsideTitles(15);
        bar.setBounds(187, 28, 650, 20);
        bar.setColorIndices(new int[] {1, 8, 4, 0});
        bar.addLeftIcon(icon);
        bar.addTitle(title);
        ContainerController head = new ContainerController();
        head.setRenderer(new ContainerRendererHigh(head));
        head.setBounds(0, 0, 1024, 0);
        head.setEntertainmentMenuTransformation(12.0f, 12.0f, 0.96f, 0.96f, 0.0f);
        head.setOpacitySet(1.0f, 0.4f);
        head.setOptionMenuTransformation(-4.0f, 7.0f, 0.6f, 0.6f, 0.0f);
        head.setSelectionMenuTransformation(391.0f, 7.0f, 0.6f, 0.6f, 0.0f);
        head.add(bar);

        StatusBarStubController stub = new StatusBarStubController();
        stub.setModelID(HqcUiIds.STATUSBAR_STUB_MODEL);
        stub.setBounds(0, 0, 100, 100);
        stub.setRenderStyle(2);
        AbstractWidgetController ref1 = f.getRefWidget(t, 1, f); // also sets refWidgets[t][2]

        s.setScreenFactory(f);
        ScreenRendererHigh sr = new ScreenRendererHigh(s);
        s.setRenderer(sr);
        sr.setFonts(FontFactory.getFonts(0, t, f.framework()));
        s.setColorIndices(new int[] {1, 2, 4, 0});
        int a = -6645094;
        int b = -5592406;
        int c = -4868683;
        s.setColorPalettes(new int[][] {
            {-1, 255, -1, -1, a, b, c, a, -1},
            {-1, 255, -65511, -5546136, a, b, c, a, -4703671},
            {-1, 255, -19456, -6123155, a, b, c, a, -6123155},
            {-1, 255, -14775126, -9594974, a, b, c, a, -9594974},
            {-1, 255, -10180096, -9200779, a, b, c, a, -9200779}});
        s.setSmallStageType(smallStage);
        s.add(ref0);
        s.add(body, 1);
        s.add(head, 2);
        s.add(stub);
        s.add(ref1, 15);
        int n = ids == null ? 0 : ids.length;
        int[] all = new int[n + 1];
        HMIView[][] allViews = new HMIView[n + 1][];
        all[0] = HqcUiIds.STATUSBAR_STUB_MODEL;
        allViews[0] = new HMIView[] {stub, f.refWidgets[t][2]};
        for (int i = 0; i < n; i++) {
            all[i + 1] = ids[i];
            allViews[i + 1] = views[i];
        }
        sortViews(all, allViews);
        s.setViews(all, allViews);
        return s;
    }

    /** Sorts {@code ids} ascending, moving {@code views} with them (a handful of entries: insertion sort). */
    static void sortViews(int[] ids, HMIView[][] views) {
        for (int i = 1; i < ids.length; i++) {
            int id = ids[i];
            HMIView[] v = views[i];
            int j = i - 1;
            while (j >= 0 && ids[j] > id) {
                ids[j + 1] = ids[j];
                views[j + 1] = views[j];
                j--;
            }
            ids[j + 1] = id;
            views[j + 1] = v;
        }
    }

    /**
     * The page's list on {@link HqcUiIds#PAGE_LIST_MODEL}: the kind column is the id and record-set column (it picks
     * the row's cells), the enabled column greys a row (spike D).
     */
    static ListController list(PageLayout layout) {
        ListController list = new ListController();
        list.setModelID(HqcUiIds.PAGE_LIST_MODEL);
        list.setBounds(0, 0, 0, 0);
        list.setGlassplateInsetsBottom(new int[0]);
        list.setGlassplateInsetsTop(new int[0]);
        list.setIdColumn(HqcUiIds.PAGE_COL_KIND);
        list.setRecordSetColumn(HqcUiIds.PAGE_COL_KIND);
        list.setEnabledColumn(HqcUiIds.PAGE_COL_ENABLED);
        list.setNoFocusAreasBottom(new int[0]);
        list.setNoFocusAreasTop(new int[0]);
        list.setItemFactory(new Rows(layout));
        return list;
    }

    /**
     * List rows by kind (spike D, {@code SpikePage.Items}): one shell (a type-16 menu item with one grid layout,
     * vertically centred), the label on model column 0, and the kind's cell: a checkbox, radio or progress bar on
     * column 1, a right-aligned value text on column 4, the item icon (bitmaps {@link PageLayout#iconIds}, indexed by
     * column 1) before the label, or icon, label and value [bench N4]. ACTION and LABEL rows are the label alone.
     */
    static final class Rows implements ListItemFactory {
        private final PageLayout layout;

        Rows(PageLayout layout) {
            this.layout = layout == null ? PageLayout.EMPTY : layout;
        }

        public AbstractWidgetController createListItem(int kind, ListCell[] cells) {
            AbstractWidgetController label = label(HqcUiIds.PAGE_COL_LABEL, HqcUiIds.ALIGN_LEFT);
            int[] gaps = HqcUiIds.PAGE_CELL_GAPS;
            float[] grow = HqcUiIds.PAGE_CELL_GROW;
            int[] min = null;
            int[] max = null;
            AbstractWidgetController[] row;
            switch (kind) {
                case PageScreens.ROW_TOGGLE:
                    row = new AbstractWidgetController[] {label, checkbox()};
                    break;
                case PageScreens.ROW_RADIO:
                    row = new AbstractWidgetController[] {label, radio()};
                    max = HqcUiIds.PAGE_RADIO_MAX;
                    break;
                case PageScreens.ROW_PROGRESS:
                    row = new AbstractWidgetController[] {label, progress()};
                    min = HqcUiIds.PAGE_PROGRESS_MIN;
                    break;
                case PageScreens.ROW_VALUE:
                    row = new AbstractWidgetController[] {label, valueLabel()};
                    break;
                case PageScreens.ROW_ICON:
                    row = new AbstractWidgetController[] {icon(), label};
                    gaps = HqcUiIds.PAGE_ICON_GAPS;
                    grow = HqcUiIds.PAGE_ICON_GROW;
                    max = HqcUiIds.PAGE_ICON_MAX;
                    break;
                case PageScreens.ROW_ICON_VALUE: // [bench N4]
                    row = new AbstractWidgetController[] {icon(), label, valueLabel()};
                    gaps = HqcUiIds.PAGE_ICON_VALUE_GAPS;
                    grow = HqcUiIds.PAGE_ICON_VALUE_GROW;
                    max = HqcUiIds.PAGE_ICON_VALUE_MAX;
                    break;
                default: // ACTION, LABEL: the label alone
                    row = new AbstractWidgetController[] {label};
                    gaps = null;
                    grow = new float[] {1.0f};
                    break;
            }
            return item(label, cols(row.length, gaps, grow, min, max), row);
        }

        public AbstractWidgetController createListItemNoData() {
            return null;
        }

        /** The label (column 0) and the value text (column 4); the other cells depend on the row kind. */
        public AbstractWidgetController createCell(int col) {
            if (col == HqcUiIds.PAGE_COL_LABEL) {
                return label(HqcUiIds.PAGE_COL_LABEL, HqcUiIds.ALIGN_LEFT);
            }
            if (col == HqcUiIds.PAGE_COL_VALUE_TEXT) {
                return valueLabel();
            }
            return null;
        }

        /** The row shell: {@code cells} (one is {@code label}) in grid columns 0.. in order, vertically centred. */
        private static MenuItemController item(AbstractWidgetController label, MenuItemColumnsConstraints cols,
                                               AbstractWidgetController[] cells) {
            MenuItemController item = new MenuItemController();
            item.setType(HqcUiIds.LIST_ITEM_TYPE);
            item.setRenderer(new MenuItemRendererHigh(item));
            GridLayout grid = new GridLayout();
            grid.setColumnConstraints(cols);
            AxisConstraints rows = new AxisConstraints(1);
            rows.alignment = new int[] {HqcUiIds.ALIGN_MIDDLE};
            grid.setRowConstraints(rows);
            GridLayoutHints[] hints = new GridLayoutHints[cells.length];
            for (int i = 0; i < cells.length; i++) {
                hints[i] = new GridLayoutHints(i, 0);
                hints[i].hidemode = 0;
                hints[i].alignmentVert = HqcUiIds.ALIGN_MIDDLE;
            }
            grid.setCellConstraints(hints, cells);
            item.setLayoutChoices(new GridLayout[] {grid});
            item.add(label); // the label first, as spike D and the stock lists add it
            for (int i = 0; i < cells.length; i++) {
                if (cells[i] != label) {
                    item.add(cells[i]);
                }
            }
            return item;
        }

        /** Column constraints; shrink as grow. Null arrays stay unset. */
        private static MenuItemColumnsConstraints cols(int n, int[] gaps, float[] grow, int[] min, int[] max) {
            MenuItemColumnsConstraints c = new MenuItemColumnsConstraints(n);
            if (gaps != null) {
                c.gaps = (int[]) gaps.clone();
            }
            c.grow = (float[]) grow.clone();
            if (n > 1) {
                c.shrink = (float[]) grow.clone();
            }
            if (min != null) {
                c.min = (int[]) min.clone();
            }
            if (max != null) {
                c.max = (int[]) max.clone();
            }
            return c;
        }

        static LabelController label(int col, int hAlign) {
            LabelController l = new LabelController();
            LabelRendererHigh r = new LabelRendererHigh(l);
            l.setRenderer(r);
            l.setModelColumn(col);
            r.setAlignment(hAlign, HqcUiIds.ALIGN_MIDDLE);
            return l;
        }

        static LabelController valueLabel() {
            return label(HqcUiIds.PAGE_COL_VALUE_TEXT, HqcUiIds.ALIGN_RIGHT);
        }

        static CheckboxController checkbox() {
            CheckboxController cb = new CheckboxController();
            cb.setRenderer(new CheckboxRendererHigh(cb));
            cb.setBounds(0, 0, HqcUiIds.CELL_BOUNDS_W, HqcUiIds.CELL_BOUNDS_H);
            cb.setMapping(HqcUiIds.CHECKBOX_MAPPING);
            cb.setModelColumn(HqcUiIds.PAGE_COL_VALUE);
            return cb;
        }

        static RadioButtonController radio() {
            RadioButtonController rb = new RadioButtonController();
            rb.setRenderer(new RadioButtonRendererHigh(rb));
            rb.setBounds(0, 0, HqcUiIds.CELL_BOUNDS_W, HqcUiIds.CELL_BOUNDS_H);
            rb.setModelColumn(HqcUiIds.PAGE_COL_VALUE);
            return rb;
        }

        static ProgressBarController progress() {
            ProgressBarController pb = new ProgressBarController();
            pb.setRenderer(new ProgressBarRendererHigh(pb));
            pb.setBitmaps(HqcUiIds.PROGRESS_BITMAPS);
            pb.setColorIndices(HqcUiIds.PROGRESS_COLOURS);
            pb.setMinimumModelValue(HqcUiIds.PROGRESS_MIN);
            pb.setMaximumModelValue(HqcUiIds.PROGRESS_MAX);
            pb.setPreferredHeight(HqcUiIds.PROGRESS_HEIGHT);
            pb.setModelColumn(HqcUiIds.PAGE_COL_VALUE);
            return pb;
        }

        IconController icon() {
            IconController ic = new IconController();
            IconRendererHigh r = new IconRendererHigh(ic);
            ic.setRenderer(r);
            ic.setBitmaps(layout.iconIds());
            ic.setModelColumn(HqcUiIds.PAGE_COL_VALUE);
            ic.setPreferredHeight(HqcUiIds.ICON_CELL_HEIGHT);
            r.setAlignment(HqcUiIds.ALIGN_LEFT, HqcUiIds.ALIGN_MIDDLE);
            return ic;
        }
    }
}
