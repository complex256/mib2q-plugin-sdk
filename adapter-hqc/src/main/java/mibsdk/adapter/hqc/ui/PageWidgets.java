package mibsdk.adapter.hqc.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.event.KeyEvent;
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
import de.esolutions.hmi.widgets.audi.evo.ScreenWidgetEVO;
import de.esolutions.hmi.widgets.audi.evo.gridlayout.AxisConstraints;
import de.esolutions.hmi.widgets.audi.evo.gridlayout.GridLayout;
import de.esolutions.hmi.widgets.audi.evo.gridlayout.GridLayoutHints;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.ContainerRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.FocusCursorRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.GlassplateRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.IconRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.LabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.MenuItemRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.ScreenRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.ScrollbarRendererKanziHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.TitleBarRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.menu.MenuRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.ContainerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.FocusCursorController;
import de.esolutions.hmi.widgets.audi.evo.widgets.GlassplateController;
import de.esolutions.hmi.widgets.audi.evo.widgets.IconController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.ScrollbarController;
import de.esolutions.hmi.widgets.audi.evo.widgets.StatusBarStubController;
import de.esolutions.hmi.widgets.audi.evo.widgets.TitleBarWidget;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.InfoLineRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.ListController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemColumnsConstraints;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.list.ListItemFactory;

/**
 * The stock side of SDK pages: spike B's page recipe (research note §8, modelled on
 * {@code EngineeringScreenBag2.engModeApplConfigBundles}) and the screen manager calls. Runs on HMIEvent only, inside
 * {@link PageScreens}' guarded calls; every widget callback catches Throwable and falls back to stock behaviour.
 *
 * <p>A page is a {@code ScreenWidgetEVO} with a body menu (glassplate, focus cursor, scrollbar) holding one
 * {@code ListController} on {@link HqcUiIds#PAGE_LIST_MODEL}, and a head title bar whose title is factory text
 * {@link HqcUiIds#TEXT_PAGE_TITLE}. Every row is a list item with one label cell (column 0): spike B rendered that
 * cell; its checkbox cell drew nothing, so toggles carry their state as text. Layout numbers (bounds, palettes,
 * fonts) are the stock bag's decoded values, as spike B used them.
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

    public Object build(String pageKey, String title, PageScreens owner) {
        SdkCarScreenFactory f = SdkCarScreenFactory.current;
        if (f == null) {
            throw new IllegalStateException("no SDK Car screen factory");
        }
        return build(f, TERMINAL, owner, pageKey);
    }

    /**
     * Shows a prebuilt page: it needs its terminal set (the cache path that does it is bypassed) and the current
     * screen's partial popups, colour scheme and screen mode (spike B, S2a).
     */
    public void show(Object screen) {
        FwHMI fw = fw();
        IScreenData cur = fw.getScreenManager(TERMINAL).getCurrentConnectedScreenData();
        Screen page = (Screen) screen;
        page.setTerminal(fw.getHMITerminal(TERMINAL));
        int[] popups = cur == null ? null : cur.getPartialPopups();
        int scheme = cur == null ? 0 : cur.getColorScheme();
        int mode = cur == null ? 0 : cur.getScreenMode();
        fw.showScreen(TERMINAL, 0, new ScreenData(HqcUiIds.PAGE_SCREEN_ID, false, false, null, false, page, popups,
                null, -1L, -1L, 0, scheme, mode, null));
    }

    /** BACK out of SDK pages: re-shows a copy of the saved stock screen data (spike B, BACK mode "reshow"). */
    public void showStock(Object data) {
        if (!(data instanceof ScreenData)) {
            throw new IllegalStateException("no stock screen data: " + data);
        }
        fw().showScreen(TERMINAL, 0, new ScreenData((ScreenData) data));
    }

    /** Rewrites the rows in place (so focus stays), then trims or extends the list. */
    public void writeRows(String[] texts, int[] kinds) {
        ListModel m = SdkCarModelBank.pageList();
        if (m == null) {
            throw new IllegalStateException("no page list model");
        }
        int had = m.getLength();
        for (int i = 0; i < texts.length; i++) {
            ListCell[] row = {TextListCell.create(texts[i]), IntegerListCell.create(0), IntegerListCell.create(kinds[i])};
            if (i < had) {
                m.setRow(i, row);
            } else {
                m.addRow(row);
            }
        }
        for (int i = had - 1; i >= texts.length; i--) {
            m.removeRow(i);
        }
    }

    // ---- the page -----------------------------------------------------------------------------------------------

    /** The page screen: tells its owner when it connects and when it leaves. */
    static final class PageScreen extends ScreenWidgetEVO {
        private final PageScreens owner;
        private final String pageKey;

        PageScreen(int id, PageScreens owner, String pageKey) {
            super(id);
            this.owner = owner;
            this.pageKey = pageKey;
        }

        public void connected(InitializationContext c) {
            super.connected(c);
            owner.screenConnected(this, pageKey); // never throws
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

    static Screen build(SdkCarScreenFactory f, int t, PageScreens owner, String pageKey) {
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

        ListController list = new ListController();
        list.setModelID(HqcUiIds.PAGE_LIST_MODEL);
        list.setBounds(0, 0, 0, 0);
        list.setGlassplateInsetsBottom(new int[0]);
        list.setGlassplateInsetsTop(new int[0]);
        list.setIdColumn(2);
        list.setNoFocusAreasBottom(new int[0]);
        list.setNoFocusAreasTop(new int[0]);
        list.setItemFactory(new Rows());

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

        ContainerController body = new ContainerController();
        body.setRenderer(new ContainerRendererHigh(body));
        body.setBounds(0, 0, 0, 0);
        body.setEntertainmentMenuTransformation(12.0f, 12.0f, 0.96f, 0.96f, 0.5f);
        body.setOpacitySet(1.0f, 0.4f);
        body.setOptionMenuTransformation(-4.0f, 7.0f, 0.6f, 0.6f, 0.5f);
        body.setSelectionMenuTransformation(391.0f, 7.0f, 0.6f, 0.6f, 0.25f);
        body.add(menu);

        IconController icon = new IconController();
        icon.setRenderer(new IconRendererHigh(icon));
        icon.setBitmaps(new int[] {HqcUiIds.PAGE_TITLE_ICON_BITMAP});
        icon.setBounds(0, 0, 100, 100);
        LabelController title = new LabelController();
        LabelRendererHigh titleR = new LabelRendererHigh(title);
        title.setRenderer(titleR);
        titleR.setFonts(FontFactory.getFonts(4, t, f.framework()));
        title.setTextIds(new int[] {HqcUiIds.TEXT_PAGE_TITLE});
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

        ScreenWidgetEVO s = new PageScreen(HqcUiIds.PAGE_SCREEN_ID, owner, pageKey);
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
        s.setSmallStageType(3);
        s.add(ref0);
        s.add(body, 1);
        s.add(head, 2);
        s.add(stub);
        s.add(ref1, 15);
        s.setViews(new int[] {HqcUiIds.STATUSBAR_STUB_MODEL, HqcUiIds.PAGE_LIST_MODEL},
                new HMIView[][] {{stub, f.refWidgets[t][2]}, {list}});
        return s;
    }

    /** List rows: one label cell (column 0) for every row kind; the kind only tells PageScreens what a press does. */
    static final class Rows implements ListItemFactory {
        public AbstractWidgetController createListItem(int kind, ListCell[] cells) {
            MenuItemController item = new MenuItemController();
            item.setType(HqcUiIds.LIST_ITEM_TYPE);
            item.setRenderer(new MenuItemRendererHigh(item));
            GridLayout grid = new GridLayout();
            MenuItemColumnsConstraints cols = new MenuItemColumnsConstraints(1);
            cols.grow = new float[] {1.0f};
            grid.setColumnConstraints(cols);
            grid.setRowConstraints(new AxisConstraints(1));
            AbstractWidgetController text = createCell(0);
            GridLayoutHints th = new GridLayoutHints(0, 0);
            th.hidemode = 0;
            grid.setCellConstraints(new GridLayoutHints[] {th}, new Object[] {text});
            item.setLayoutChoices(new GridLayout[] {grid});
            item.add(text);
            return item;
        }

        public AbstractWidgetController createListItemNoData() {
            return null;
        }

        public AbstractWidgetController createCell(int col) {
            if (col != 0) {
                return null;
            }
            LabelController l = new LabelController();
            l.setRenderer(new LabelRendererHigh(l));
            l.setModelColumn(0);
            return l;
        }
    }
}
