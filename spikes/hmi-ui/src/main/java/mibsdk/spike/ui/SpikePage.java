package mibsdk.spike.ui;

import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.event.KeyEvent;
import de.audi.atip.hmi.event.RunnableEvent;
import de.audi.atip.hmi.model.AbstractModelBank;
import de.audi.atip.hmi.model.IntegerListCell;
import de.audi.atip.hmi.model.ListCell;
import de.audi.atip.hmi.model.ListListener;
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
import de.esolutions.hmi.widgets.audi.evo.high.widgets.CheckboxRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.CompositeRendererHigh;
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
import de.esolutions.hmi.widgets.audi.evo.widgets.CheckboxController;
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
 * Spike B (S2): an SDK page, a Screen built at runtime and modelled on EngineeringScreenBag2.engModeApplConfigBundles.
 * A MenuController holds one static LABEL row (LabelModel) and a ListController on an SDK ListModel whose rows are
 * 3 ACTION rows and 2 CHECKBOX rows (IntegerListCell). S2a shows it with FwHMI.showScreen and a prebuilt Screen;
 * S2b returns it from createScreen for the screen of a hijacked Car SMM state.
 *
 * Float and colour values are the decoded (byte-swapped back) constants of the stock bag.
 */
final class SpikePage {
    /** BACK mode for the S2a page: 0 re-show the screen the page came from, 1 fire the drawer's HK return event, 2 stock. */
    static volatile int backMode;
    /** S2b: the Car SMM event to fire. */
    static volatile int hijackEvent = SpikeIds.HIJACK_EVENT;
    /** S2a: build without the static LABEL row (in case mixing it with the list breaks the menu). */
    static volatile boolean noLabelRow;

    private static volatile long hijackArmedAt;
    /** The screen data the S2a page was shown from (HMIEvent only). */
    private static IScreenData from;

    private static final int KIND_ACTION = 0;
    private static final int KIND_CHECKBOX = 1;
    private static final String[] ROW_TEXT = {"Action A", "Action B", "Action C", "Checkbox 1", "Checkbox 2"};
    private static final int[] ROW_KIND = {KIND_ACTION, KIND_ACTION, KIND_ACTION, KIND_CHECKBOX, KIND_CHECKBOX};

    private SpikePage() {
    }

    // ---- models -------------------------------------------------------------------------------------------------

    static ListModel createListModel(int id) {
        final ListModel l = new ListModel(id);
        l.setMaxColumns(3);
        l.setMaxRows(24);
        for (int i = 0; i < ROW_TEXT.length; i++) {
            l.addRow(new ListCell[] {TextListCell.create(ROW_TEXT[i]), IntegerListCell.create(0),
                    IntegerListCell.create(ROW_KIND[i])});
        }
        l.setListListener(new ListListener() {
            public void itemSelected(int modelId, int row, int x, int terminal) {
                try {
                    SpikeLog.log("page-select row=" + row + " x=" + x + " terminal=" + terminal + " thread="
                            + Thread.currentThread().getName());
                    if (row >= 0 && row < ROW_KIND.length && ROW_KIND[row] == KIND_CHECKBOX) {
                        int v = ((IntegerListCell) l.getCell(row, 1)).getValue() == 0 ? 1 : 0;
                        l.setCell(row, 1, IntegerListCell.create(v));
                        SpikeLog.log("page-toggle row=" + row + " value=" + v);
                    }
                } catch (Throwable t) {
                    SpikeLog.log("page-select FAILED: " + t);
                }
            }

            public void itemFocused(int modelId, int row, int x, int terminal) {
                SpikeLog.log("page-focus row=" + row);
            }

            public void itemReleased(int modelId, int row, int x, int terminal) {
            }
        });
        return l;
    }

    // ---- S2a: showScreen with a prebuilt Screen -----------------------------------------------------------------

    static void postShowDirect() {
        post(new Runnable() {
            public void run() {
                try {
                    showDirect();
                } catch (Throwable t) {
                    SpikeLog.log("page-show FAILED: " + t);
                }
            }
        });
    }

    private static void showDirect() {
        FwHMI fw = fw();
        IScreenManager sm = fw.getScreenManager(0);
        IScreenData cur = sm.getCurrentConnectedScreenData();
        IScreenData prev = sm.getPreviousConnectedScreenData();
        SpikeLog.log("page-show S2a from current=" + describe(cur) + " previous=" + describe(prev) + " thread="
                + Thread.currentThread().getName());
        from = cur;
        long t0 = System.currentTimeMillis();
        Screen page = build(SpikeFactory.instance, SpikeIds.PAGE_SCREEN_ID, 0, true);
        // The cache path (AbstractTerminalContext.getScreenFromCache) sets the terminal; a prebuilt screen needs it too.
        page.setTerminal(fw.getHMITerminal(0));
        int[] popups = cur == null ? null : cur.getPartialPopups();
        int scheme = cur == null ? 0 : cur.getColorScheme();
        int mode = cur == null ? 0 : cur.getScreenMode();
        ScreenData sd = new ScreenData(SpikeIds.PAGE_SCREEN_ID, false, false, null, false, page, popups, null, -1L,
                -1L, 0, scheme, mode, null);
        SpikeLog.log("page-show S2a screenData popups=" + (popups == null ? -1 : popups.length) + " colorScheme="
                + scheme + " screenMode=" + mode);
        fw.showScreen(0, 0, sd);
        SpikeLog.log("page-show S2a showScreen done build+show=" + (System.currentTimeMillis() - t0) + "ms now="
                + describe(sm.getCurrentConnectedScreenData()));
    }

    private static void back(String how) {
        FwHMI fw = fw();
        IScreenManager sm = fw.getScreenManager(0);
        IScreenData f = from;
        SpikeLog.log("page-back mode=" + how + " current=" + describe(sm.getCurrentConnectedScreenData())
                + " previous=" + describe(sm.getPreviousConnectedScreenData()) + " target=" + describe(f));
        if (f instanceof ScreenData) {
            fw.showScreen(0, 0, new ScreenData((ScreenData) f));
        }
    }

    // ---- S2b: hijack a Car state's screen -----------------------------------------------------------------------

    static void hijack() {
        int ev = hijackEvent;
        hijackArmedAt = System.currentTimeMillis();
        SpikeLog.log("page-hijack S2b firing event " + ev);
        fw().fireSMEvent(0, ev);
    }

    /** Called from SpikeFactory.createScreen: the SDK page if a hijack is armed (within 3 s), else null. */
    static Screen hijackScreen(SpikeFactory f, int id, int terminal) {
        long armed = hijackArmedAt;
        if (armed == 0 || System.currentTimeMillis() - armed > 3000 || id / 100000 != SpikeIds.CAR_MODULE) {
            return null;
        }
        hijackArmedAt = 0;
        SpikeLog.log("page-hijack S2b createScreen(" + id + ") -> SDK page");
        return build(f, id, terminal, false);
    }

    // ---- the page -----------------------------------------------------------------------------------------------

    /** ScreenWidgetEVO that logs what its (exception-swallowing) connect throws. */
    static final class PageScreen extends ScreenWidgetEVO {
        PageScreen(int id) {
            super(id);
        }

        public void connected(InitializationContext c) {
            try {
                super.connected(c);
                SpikeLog.log("page connected id=" + getID());
            } catch (RuntimeException e) {
                java.io.StringWriter w = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(w));
                String t = w.toString();
                SpikeLog.log("page connect FAILED: " + (t.length() > 1500 ? t.substring(0, 1500) : t));
                throw e;
            }
        }
    }

    /** A MenuController that logs keys and, on the S2a page, handles BACK (key 15) itself. */
    static final class PageMenu extends MenuController {
        private final boolean ownBack;

        PageMenu(boolean ownBack) {
            this.ownBack = ownBack;
        }

        public void keyPressed(KeyEvent e) {
            int code = e.getKeyCode();
            SpikeLog.log("page-key press code=" + code + " ownBack=" + ownBack);
            if (ownBack && code == SpikeIds.KEY_RETURN && backMode != 2) {
                e.consume(true);
                if (backMode == 0) {
                    post(new Runnable() {
                        public void run() {
                            try {
                                back("reshow");
                            } catch (Throwable t) {
                                SpikeLog.log("page-back FAILED: " + t);
                            }
                        }
                    });
                } else {
                    SpikeLog.log("page-back mode=hk-return-event " + SpikeIds.DRAWER_HK_RETURN_EVENT);
                    fw().fireSMEvent(0, SpikeIds.DRAWER_HK_RETURN_EVENT);
                }
                return;
            }
            super.keyPressed(e);
        }

        public void keyReleased(KeyEvent e) {
            if (ownBack && e.getKeyCode() == SpikeIds.KEY_RETURN && backMode != 2) {
                e.consume(true);
                return;
            }
            super.keyReleased(e);
        }
    }

    static Screen build(SpikeFactory f, int screenId, int t, boolean ownBack) {
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
        list.setModelID(SpikeIds.PAGE_LIST_MODEL);
        list.setBounds(0, 0, 0, 0);
        list.setGlassplateInsetsBottom(new int[0]);
        list.setGlassplateInsetsTop(new int[0]);
        list.setIdColumn(2);
        list.setNoFocusAreasBottom(new int[0]);
        list.setNoFocusAreasTop(new int[0]);
        list.setItemFactory(new Items());

        MenuItemController label = null;
        if (!noLabelRow) {
            label = new MenuItemController();
            label.setRenderer(new CompositeRendererHigh(label));
            label.setModelID(SpikeIds.PAGE_LABEL_MODEL);
            label.setLabelId(SpikeIds.TEXT_PAGE_INFO);
            label.setGlassplateInsetsBottom(6);
            label.setGlassplateInsetsTop(7);
            label.setInternalID(609950);
            label.setType(SpikeIds.ROW_TYPE_LABEL);
            label.setWidgetID(0);
        }

        PageMenu menu = new PageMenu(ownBack);
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
        if (label != null) {
            menu.add(label);
        }
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
        icon.setBitmaps(new int[] {127});
        icon.setBounds(0, 0, 100, 100);
        LabelController title = new LabelController();
        LabelRendererHigh titleR = new LabelRendererHigh(title);
        title.setRenderer(titleR);
        titleR.setFonts(FontFactory.getFonts(4, t, f.framework()));
        title.setTextIds(new int[] {SpikeIds.TEXT_PAGE_TITLE});
        title.setBounds(0, 0, 0, 30);
        title.setColorIndices(new int[] {1, 2, 4, 0});
        TitleBarWidget bar = new TitleBarWidget();
        bar.setRenderer(new TitleBarRendererHigh(bar));
        bar.setBitmaps(new int[] {58, 58});
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
        stub.setModelID(SpikeIds.STATUSBAR_STUB_MODEL);
        stub.setBounds(0, 0, 100, 100);
        stub.setRenderStyle(2);
        AbstractWidgetController ref1 = f.getRefWidget(t, 1, f);   // also sets refWidgets[t][2]

        ScreenWidgetEVO s = new PageScreen(screenId);
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
        if (label != null) {
            s.setViews(new int[] {SpikeIds.STATUSBAR_STUB_MODEL, SpikeIds.PAGE_LIST_MODEL, SpikeIds.PAGE_LABEL_MODEL},
                    new HMIView[][] {{stub, f.refWidgets[t][2]}, {list}, {label}});
        } else {
            s.setViews(new int[] {SpikeIds.STATUSBAR_STUB_MODEL, SpikeIds.PAGE_LIST_MODEL},
                    new HMIView[][] {{stub, f.refWidgets[t][2]}, {list}});
        }
        SpikeLog.log("page built id=" + screenId + " ownBack=" + ownBack + " labelRow=" + (label != null));
        return s;
    }

    /** List rows: kind 0 = ACTION (label only), kind 1 = CHECKBOX (label + checkbox on column 1). */
    static final class Items implements ListItemFactory {
        public AbstractWidgetController createListItem(int kind, ListCell[] cells) {
            boolean box = kind == KIND_CHECKBOX;
            MenuItemController item = new MenuItemController();
            item.setType(SpikeIds.ROW_TYPE_LIST_ITEM);
            item.setRenderer(new MenuItemRendererHigh(item));
            GridLayout grid = new GridLayout();
            MenuItemColumnsConstraints cols = new MenuItemColumnsConstraints(box ? 2 : 1);
            if (box) {
                cols.gaps = new int[] {0, 22, 0};
                cols.grow = new float[] {1.0f, 0.0f};
                cols.shrink = new float[] {1.0f, 0.0f};
            } else {
                cols.grow = new float[] {1.0f};
            }
            grid.setColumnConstraints(cols);
            grid.setRowConstraints(new AxisConstraints(1));
            AbstractWidgetController text = createCell(0);
            GridLayoutHints th = new GridLayoutHints(0, 0);
            th.hidemode = 0;
            if (box) {
                AbstractWidgetController check = createCell(1);
                GridLayoutHints ch = new GridLayoutHints(1, 0);
                ch.hidemode = 0;
                grid.setCellConstraints(new GridLayoutHints[] {th, ch}, new Object[] {text, check});
                item.setLayoutChoices(new GridLayout[] {grid});
                item.add(text);
                item.add(check);
            } else {
                grid.setCellConstraints(new GridLayoutHints[] {th}, new Object[] {text});
                item.setLayoutChoices(new GridLayout[] {grid});
                item.add(text);
            }
            return item;
        }

        public AbstractWidgetController createListItemNoData() {
            return null;
        }

        public AbstractWidgetController createCell(int col) {
            if (col == 0) {
                LabelController l = new LabelController();
                l.setRenderer(new LabelRendererHigh(l));
                l.setModelColumn(0);
                return l;
            }
            if (col == 1) {
                CheckboxController cb = new CheckboxController();
                cb.setRenderer(new CheckboxRendererHigh(cb));
                cb.setBounds(0, 0, 100, 100);
                cb.setMapping(new int[][] {{0}, {1}});
                cb.setModelColumn(1);
                return cb;
            }
            return null;
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static FwHMI fw() {
        return (FwHMI) SpikeFactory.instance.framework().getHmiServiceApp();
    }

    private static void post(Runnable r) {
        HMIService svc = AbstractModelBank.getHMIservice();
        svc.getEventDispatcher().postEvent(new RunnableEvent(false, r));
    }

    private static String describe(IScreenData d) {
        if (d == null) {
            return "null";
        }
        return "id=" + d.getId() + (d.getScreen() == null ? "" : " screen=" + d.getScreen().getClass().getName());
    }
}
