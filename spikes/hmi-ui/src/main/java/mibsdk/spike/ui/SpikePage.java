package mibsdk.spike.ui;

import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.event.JoystickEvent;
import de.audi.atip.hmi.event.KeyEvent;
import de.audi.atip.hmi.event.RunnableEvent;
import de.audi.atip.hmi.event.WheelButtonEvent;
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
    static volatile boolean noLabelRow = true;   // spike D: off by default ("label-on" brings it back)

    private static volatile long hijackArmedAt;
    /** The screen data the S2a page was shown from (HMIEvent only). */
    private static IScreenData from;

    static final int KIND_ACTION = 0;
    static final int KIND_CHECKBOX = 1;
    static final int KIND_RADIO = 2;
    static final int KIND_PROGRESS = 3;
    static final int KIND_VALUE = 4;
    static final int KIND_ICON = 5;
    /** Spike D list columns: 0 text, 1 int value, 2 kind (record set), 3 enabled, 4 value text. */
    static final int COL_TEXT = 0;
    static final int COL_VALUE = 1;
    static final int COL_KIND = 2;
    static final int COL_ENABLED = 3;
    static final int COL_VALUE_TEXT = 4;
    static final int COLUMNS = 5;
    static final String[] ROW_TEXT = {"Action A", "Action B", "Checkbox 1", "Checkbox 2", "Checkbox 3",
        "Checkbox off", "Radio A", "Radio B", "Radio C", "Progress", "Language", "Icon row"};
    static final int[] ROW_KIND = {KIND_ACTION, KIND_ACTION, KIND_CHECKBOX, KIND_CHECKBOX, KIND_CHECKBOX,
        KIND_CHECKBOX, KIND_RADIO, KIND_RADIO, KIND_RADIO, KIND_PROGRESS, KIND_VALUE, KIND_ICON};
    private static final int[] ROW_VALUE = {0, 0, 0, 0, 1, 0, 1, 0, 0, 60, 0, 0};
    private static final int[] ROW_ENABLED = {1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1};
    static final int ROW_CHECKBOX_1 = 2;
    static final int ROW_PROGRESS = 9;
    /** Icon cell bitmaps, indexed by the cell value (two SDK ids served by getImagePath, one stock id). */
    static final int[] ICON_CELL_BITMAPS = {SpikeIds.ICON_POP_A, SpikeIds.ICON_POP_B, SpikeIds.ICON_PLUG};
    /** The page's ListModel (created by the bank). */
    static volatile ListModel pageList;

    private SpikePage() {
    }

    // ---- models -------------------------------------------------------------------------------------------------

    static ListModel createListModel(int id) {
        final ListModel l = new ListModel(id);
        l.setMaxColumns(COLUMNS);
        l.setMaxRows(24);
        for (int i = 0; i < ROW_TEXT.length; i++) {
            l.addRow(new ListCell[] {TextListCell.create(ROW_TEXT[i]), IntegerListCell.create(ROW_VALUE[i]),
                    IntegerListCell.create(ROW_KIND[i]), IntegerListCell.create(ROW_ENABLED[i]),
                    TextListCell.create(ROW_KIND[i] == KIND_VALUE ? "English" : "")});
        }
        l.setListListener(new ListListener() {
            public void itemSelected(int modelId, int row, int x, int terminal) {
                try {
                    SpikeLog.log("page-select row=" + row + " x=" + x + " terminal=" + terminal + " thread="
                            + Thread.currentThread().getName());
                    select(l, row);
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
        pageList = l;
        return l;
    }

    static int intCell(ListModel l, int row, int col) {
        ListCell c = l.getCell(row, col);
        return c instanceof IntegerListCell ? ((IntegerListCell) c).getValue() : -1;
    }

    /** The plugin side of a press: the SDK decides and writes the new value; the cell follows the model. */
    private static void select(ListModel l, int row) {
        int kind = intCell(l, row, COL_KIND);
        int v = intCell(l, row, COL_VALUE);
        if (kind == KIND_CHECKBOX) {
            int nv = v == 0 ? 1 : 0;
            l.setCell(row, COL_VALUE, IntegerListCell.create(nv));
            SpikeLog.log("page-toggle row=" + row + " value=" + nv);
        } else if (kind == KIND_RADIO) {
            for (int r = 0; r < ROW_KIND.length; r++) {
                if (intCell(l, r, COL_KIND) == KIND_RADIO) {
                    int want = r == row ? 1 : 0;
                    if (intCell(l, r, COL_VALUE) != want) {
                        l.setCell(r, COL_VALUE, IntegerListCell.create(want));
                    }
                }
            }
            SpikeLog.log("page-radio row=" + row + " selected");
        } else if (kind == KIND_PROGRESS) {
            progressNext("select");
        } else if (kind == KIND_ICON) {
            int nv = (v + 1) % ICON_CELL_BITMAPS.length;
            l.setCell(row, COL_VALUE, IntegerListCell.create(nv));
            SpikeLog.log("page-icon row=" + row + " index=" + nv + " bitmap=" + ICON_CELL_BITMAPS[nv]);
        } else {
            SpikeLog.log("page-action row=" + row + " kind=" + kind);
        }
    }

    /** Progress row: +20 (0..100, wrapping at 120 to 0). */
    static void progressNext(String why) {
        ListModel l = pageList;
        if (l == null) {
            SpikeLog.log("page-progress no model");
            return;
        }
        int v = intCell(l, ROW_PROGRESS, COL_VALUE);
        int nv = (v + 20) % 120;
        l.setCell(ROW_PROGRESS, COL_VALUE, IntegerListCell.create(nv));
        SpikeLog.log("page-progress " + why + " " + v + " -> " + nv + " thread=" + Thread.currentThread().getName());
    }

    // ---- S2a: showScreen with a prebuilt Screen -----------------------------------------------------------------

    static void postShowDirect() {
        postShowPage(true, "drawer");
    }

    /** Shows the page on HMIEvent; saveFrom: remember the current screen for the page's own BACK. */
    static void postShowPage(final boolean saveFrom, final String why) {
        post(new Runnable() {
            public void run() {
                try {
                    showPage(saveFrom, why);
                } catch (Throwable t) {
                    SpikeLog.log("page-show FAILED: " + t);
                }
            }
        });
    }

    private static void showPage(boolean saveFrom, String why) {
        IScreenManager sm = fw().getScreenManager(0);
        IScreenData cur = sm.getCurrentConnectedScreenData();
        SpikeLog.log("page-show " + why + " from current=" + describe(cur) + " previous="
                + describe(sm.getPreviousConnectedScreenData()) + " saveFrom=" + saveFrom + " thread="
                + Thread.currentThread().getName());
        if (saveFrom) {
            from = cur;
        }
        long t0 = System.currentTimeMillis();
        Screen page = build(SpikeFactory.instance, SpikeIds.PAGE_SCREEN_ID, 0, true);
        showBuilt(page, SpikeIds.PAGE_SCREEN_ID);
        SpikeLog.log("page-show done build+show=" + (System.currentTimeMillis() - t0) + "ms now="
                + describe(sm.getCurrentConnectedScreenData()));
    }

    /** showScreen of a prebuilt Screen (on HMIEvent): the current screen's popups, colour scheme and mode. */
    static void showBuilt(Screen s, int id) {
        FwHMI fw = fw();
        IScreenData cur = fw.getScreenManager(0).getCurrentConnectedScreenData();
        // The cache path (AbstractTerminalContext.getScreenFromCache) sets the terminal; a prebuilt screen needs it too.
        s.setTerminal(fw.getHMITerminal(0));
        int[] popups = cur == null ? null : cur.getPartialPopups();
        int scheme = cur == null ? 0 : cur.getColorScheme();
        int mode = cur == null ? 0 : cur.getScreenMode();
        ScreenData sd = new ScreenData(id, false, false, null, false, s, popups, null, -1L, -1L, 0, scheme, mode, null);
        fw.showScreen(0, 0, sd);
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

    /**
     * A MenuController that logs keys and handles BACK (key 15) itself: on the S2a page by backMode, on a spike D
     * screen by its onBack runnable (posted to HMIEvent).
     */
    static final class PageMenu extends MenuController {
        private final boolean ownBack;
        private final Runnable onBack;
        private final String name;

        PageMenu(boolean ownBack) {
            this.ownBack = ownBack;
            this.onBack = null;
            this.name = "page";
        }

        PageMenu(String name, Runnable onBack) {
            this.ownBack = true;
            this.onBack = onBack;
            this.name = name;
        }

        public void keyPressed(KeyEvent e) {
            int code = e.getKeyCode();
            SpikeLog.log(name + "-key press code=" + code + " ownBack=" + ownBack);
            if (onBack != null && code == SpikeIds.KEY_RETURN) {
                e.consume(true);
                post(onBack);
                return;
            }
            if (onBack == null && ownBack && code == SpikeIds.KEY_RETURN && backMode != 2) {
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
            if (ownBack && e.getKeyCode() == SpikeIds.KEY_RETURN && (onBack != null || backMode != 2)) {
                e.consume(true);
                return;
            }
            super.keyReleased(e);
        }
    }

    /** Spike D: append the typed rows (type 8, 64, 128) to the page menu after the list. */
    static volatile boolean typedRows = true;
    /** Spike D: the list's record-set (kind) column and enabled column; "rs-off" reproduces spike B's S2c. */
    static volatile boolean recordSet = true;

    static Screen build(SpikeFactory f, int screenId, int t, boolean ownBack) {
        AbstractWidgetController ref0 = f.getRefWidget(t, 0, f);

        ListController list = new ListController();
        list.setModelID(SpikeIds.PAGE_LIST_MODEL);
        list.setBounds(0, 0, 0, 0);
        list.setGlassplateInsetsBottom(new int[0]);
        list.setGlassplateInsetsTop(new int[0]);
        list.setIdColumn(COL_KIND);
        if (recordSet) {
            // The fix (native-inputs note §1): the item factory's kind comes from the record-set column.
            list.setRecordSetColumn(COL_KIND);
            list.setEnabledColumn(COL_ENABLED);
        }
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
        dressMenu(menu, f, t, 32);
        if (label != null) {
            menu.add(label);
        }
        menu.add(list);

        MenuItemController row8 = null;
        MenuItemController combo = null;
        MenuItemController rotary = null;
        if (typedRows) {
            // Type-8 checkbox row on its own ChoiceModel (MenuItemBuilder.setupCheckboxMenuItem at connect).
            row8 = new MenuItemController();
            row8.setRenderer(new CompositeRendererHigh(row8));
            row8.setModelID(SpikeIds.ROW8_MODEL);
            row8.setLabelId(SpikeIds.TEXT_ROW8);
            row8.setGlassplateInsetsBottom(6);
            row8.setGlassplateInsetsTop(7);
            row8.setInternalID(609960);
            row8.setType(SpikeIds.ROW_TYPE_CHECKBOX);
            row8.setWidgetID(-1);
            // Type-64 combo row (stock tONESOUNDEFFECTMAIN / CarScreenBag3 recipe; carplay-rgi UpmixToneUi).
            combo = new MenuItemController();
            CompositeRendererHigh comboR = new CompositeRendererHigh(combo);
            combo.setRenderer(comboR);
            int[] comboBitmaps = new int[44];
            for (int i = 0; i < 42; i++) {
                comboBitmaps[i] = 127;
            }
            comboBitmaps[42] = 129;
            comboBitmaps[43] = 332;
            combo.setBitmaps(comboBitmaps);
            comboR.setFonts(FontFactory.getFonts(5, t, f.framework()));
            combo.setModelID(SpikeIds.COMBO_MODEL);
            combo.setTextIds(new int[] {SpikeIds.TEXT_COMBO_OPT0, SpikeIds.TEXT_COMBO_OPT0 + 1,
                SpikeIds.TEXT_COMBO_OPT0 + 2, SpikeIds.TEXT_COMBO_OPT0 + 3});
            combo.setLabelId(SpikeIds.TEXT_COMBO_LABEL);
            combo.setGlassplateInsetsBottom(6);
            combo.setGlassplateInsetsTop(7);
            combo.setInternalID(609961);
            combo.setType(SpikeIds.ROW_TYPE_COMBO);
            combo.setWidgetID(1);
            // Type-128 rotary row (display) on the RangeModel; its key handler opens the slider screen.
            rotary = new MenuItemController();
            rotary.setRenderer(new CompositeRendererHigh(rotary));
            rotary.setModelID(SpikeIds.RANGE_MODEL);
            rotary.setLabelId(SpikeIds.TEXT_BASS);
            rotary.setGlassplateInsetsBottom(6);
            rotary.setGlassplateInsetsTop(7);
            rotary.setHasInfolineDisclaimer(false);
            rotary.setInternalID(609962);
            rotary.setType(SpikeIds.ROW_TYPE_ROTARY);
            rotary.setKeyHandler(new SliderOpenKeyHandler());
            menu.add(row8);
            menu.add(combo);
            menu.add(rotary);
        }

        // Views: sorted ids (findViews binary-searches). Typed rows: id -> {row}; the builder adds the inner widget.
        int n = 2 + (label != null ? 1 : 0) + (typedRows ? 3 : 0);
        int[] ids = new int[n];
        HMIView[][] views = new HMIView[n][];
        int k = 0;
        ids[k] = SpikeIds.PAGE_LIST_MODEL;
        views[k++] = new HMIView[] {list};
        if (label != null) {
            ids[k] = SpikeIds.PAGE_LABEL_MODEL;
            views[k++] = new HMIView[] {label};
        }
        if (typedRows) {
            ids[k] = SpikeIds.ROW8_MODEL;
            views[k++] = new HMIView[] {row8};
            ids[k] = SpikeIds.COMBO_MODEL;
            views[k++] = new HMIView[] {combo};
            ids[k] = SpikeIds.RANGE_MODEL;
            views[k++] = new HMIView[] {rotary};
        }
        ScreenWidgetEVO s = shell(null, f, screenId, t, ref0, menu, SpikeIds.TEXT_PAGE_TITLE, 0.4f, 3, ids, views);
        SpikeLog.log("page built id=" + screenId + " ownBack=" + ownBack + " labelRow=" + (label != null)
                + " recordSet=" + recordSet + " typedRows=" + typedRows);
        return s;
    }

    /** The page's MenuController settings (EngineeringScreenBag2 / Car settings recipe) plus glass, cursor, scrollbar. */
    static void dressMenu(MenuController menu, SpikeFactory f, int t, int rightOffset) {
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

        menu.setRenderer(new MenuRendererHigh(menu));
        menu.createInfolineWidgets(new InfoLineRendererHigh());
        menu.getInfoline().getInfolineRenderer().setFonts(FontFactory.getFonts(2, t, f.framework()));
        menu.getInfolineTimer().setIdleTime(800);
        menu.getInfoline().setColorIndices(new int[] {2, 0, 4, 3});
        menu.getLayout().setBackgroundInsetsVert(7);
        menu.getLayout().setContentLeftOffset(12);
        menu.getLayout().setContentRightOffset(rightOffset);
        menu.getLayout().setContentRightOffsetSmallStage(25);
        menu.getLayout().setItemsGap(9);
        menu.getLayout().setOptionsIconAdditionalRightInsets(26);
        menu.setBounds(59, 77, 946, 324);
        menu.setColorIndices(new int[] {1, 0, 4, 0, 2, 3});
        menu.add(glass, 4);
        menu.add(cursor, 1);
        menu.add(scroll, 3);
    }

    /**
     * The screen around a body widget: body container (layer 1), title bar (layer 2), status-bar stub, ref widgets,
     * palettes. ids/views: the screen's own views, sorted, all above 138 (the stub's id goes first).
     */
    static ScreenWidgetEVO shell(ScreenWidgetEVO screen, SpikeFactory f, int screenId, int t,
            AbstractWidgetController ref0,
            AbstractWidgetController bodyChild, int titleText, float dimOpacity, int smallStage, int[] ids,
            HMIView[][] views) {
        ContainerController body = new ContainerController();
        body.setRenderer(new ContainerRendererHigh(body));
        body.setBounds(0, 0, 0, 0);
        body.setEntertainmentMenuTransformation(12.0f, 12.0f, 0.96f, 0.96f, 0.5f);
        body.setOpacitySet(1.0f, dimOpacity);
        body.setOptionMenuTransformation(-4.0f, 7.0f, 0.6f, 0.6f, 0.5f);
        body.setSelectionMenuTransformation(391.0f, 7.0f, 0.6f, 0.6f, 0.25f);
        body.add(bodyChild);

        IconController icon = new IconController();
        icon.setRenderer(new IconRendererHigh(icon));
        icon.setBitmaps(new int[] {127});
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
        head.setOpacitySet(1.0f, dimOpacity);
        head.setOptionMenuTransformation(-4.0f, 7.0f, 0.6f, 0.6f, 0.0f);
        head.setSelectionMenuTransformation(391.0f, 7.0f, 0.6f, 0.6f, 0.0f);
        head.add(bar);

        StatusBarStubController stub = new StatusBarStubController();
        stub.setModelID(SpikeIds.STATUSBAR_STUB_MODEL);
        stub.setBounds(0, 0, 100, 100);
        stub.setRenderStyle(2);
        AbstractWidgetController ref1 = f.getRefWidget(t, 1, f);   // also sets refWidgets[t][2]

        ScreenWidgetEVO s = screen != null ? screen : new PageScreen(screenId);
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
        all[0] = SpikeIds.STATUSBAR_STUB_MODEL;
        allViews[0] = new HMIView[] {stub, f.refWidgets[t][2]};
        for (int i = 0; i < n; i++) {
            all[i + 1] = ids[i];
            allViews[i + 1] = views[i];
        }
        s.setViews(all, allViews);
        return s;
    }

    /** Type-128 row: SELECT (key 17) opens the slider screen; nothing else (no SMM event). */
    static final class SliderOpenKeyHandler implements IWidgetKeyHandler {
        public void keyPressed(AbstractWidgetController w, KeyEvent e) {
            try {
                if (e == null || e.getKeyCode() != SpikeIds.KEY_ENTER) {
                    return;
                }
                e.consume(false);
                SpikeLog.log("row128-press thread=" + Thread.currentThread().getName());
                SpikeScreens.postShow(SpikeIds.SLIDER_SCREEN_ID, "row128");
            } catch (Throwable t) {
                SpikeLog.log("row128-press FAILED: " + t);
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
     * List rows by kind (the record set): 0 ACTION label; 1 label + checkbox (Factory settings recipe); 2 label +
     * radio (Language recipe); 3 label + progress bar (Media recipe); 4 label + right-aligned value label (column 4);
     * 5 icon (column 1 = bitmap index) + label.
     */
    static final class Items implements ListItemFactory {
        public AbstractWidgetController createListItem(int kind, ListCell[] cells) {
            SpikeLog.log("items createListItem kind=" + kind);
            MenuItemController item = new MenuItemController();
            item.setType(SpikeIds.ROW_TYPE_LIST_ITEM);
            item.setRenderer(new MenuItemRendererHigh(item));
            GridLayout grid = new GridLayout();
            AbstractWidgetController text = label(COL_TEXT, 1);
            AbstractWidgetController second = null;
            MenuItemColumnsConstraints cols;
            boolean secondFirst = false;
            if (kind == KIND_CHECKBOX) {
                second = checkbox();
                cols = cols2(new int[] {0, 22, 0}, new float[] {1.0f, 0.0f}, null, null);
            } else if (kind == KIND_RADIO) {
                second = radio();
                cols = cols2(new int[] {0, 22, 0}, new float[] {1.0f, 0.0f}, null, new int[] {-1, 33});
            } else if (kind == KIND_PROGRESS) {
                second = progress();
                cols = cols2(new int[] {0, 22, 0}, new float[] {1.0f, 0.0f}, new int[] {-1, 300}, null);
            } else if (kind == KIND_VALUE) {
                second = label(COL_VALUE_TEXT, 3);
                cols = cols2(new int[] {0, 22, 0}, new float[] {1.0f, 0.0f}, null, null);
            } else if (kind == KIND_ICON) {
                second = icon();
                secondFirst = true;
                cols = cols2(new int[] {0, 15, 0}, new float[] {0.0f, 1.0f}, null, new int[] {30, -1});
            } else {
                cols = new MenuItemColumnsConstraints(1);
                cols.grow = new float[] {1.0f};
            }
            grid.setColumnConstraints(cols);
            AxisConstraints rows = new AxisConstraints(1);
            rows.alignment = new int[] {5};
            grid.setRowConstraints(rows);
            if (second == null) {
                GridLayoutHints th = new GridLayoutHints(0, 0);
                th.hidemode = 0;
                grid.setCellConstraints(new GridLayoutHints[] {th}, new Object[] {text});
                item.setLayoutChoices(new GridLayout[] {grid});
                item.add(text);
                return item;
            }
            GridLayoutHints th = new GridLayoutHints(secondFirst ? 1 : 0, 0);
            th.hidemode = 0;
            th.alignmentVert = 5;
            GridLayoutHints sh = new GridLayoutHints(secondFirst ? 0 : 1, 0);
            sh.hidemode = 0;
            sh.alignmentVert = 5;
            grid.setCellConstraints(new GridLayoutHints[] {th, sh}, new Object[] {text, second});
            item.setLayoutChoices(new GridLayout[] {grid});
            item.add(text);
            item.add(second);
            return item;
        }

        private static MenuItemColumnsConstraints cols2(int[] gaps, float[] grow, int[] min, int[] max) {
            MenuItemColumnsConstraints c = new MenuItemColumnsConstraints(2);
            c.gaps = gaps;
            c.grow = grow;
            c.shrink = new float[] {grow[0], grow[1]};
            if (min != null) {
                c.min = min;
            }
            if (max != null) {
                c.max = max;
            }
            return c;
        }

        public AbstractWidgetController createListItemNoData() {
            return null;
        }

        public AbstractWidgetController createCell(int col) {
            if (col == COL_TEXT) {
                return label(COL_TEXT, 1);
            }
            if (col == COL_VALUE) {
                return checkbox();
            }
            return null;
        }

        /** Label on a column; hAlign 1 left, 3 right. */
        static LabelController label(int col, int hAlign) {
            LabelController l = new LabelController();
            LabelRendererHigh r = new LabelRendererHigh(l);
            l.setRenderer(r);
            l.setModelColumn(col);
            r.setAlignment(hAlign, 5);
            return l;
        }

        static CheckboxController checkbox() {
            CheckboxController cb = new CheckboxController();
            cb.setRenderer(new CheckboxRendererHigh(cb));
            cb.setBounds(0, 0, 100, 100);
            cb.setMapping(new int[][] {{0}, {1}});
            cb.setModelColumn(COL_VALUE);
            return cb;
        }

        static RadioButtonController radio() {
            RadioButtonController rb = new RadioButtonController();
            rb.setRenderer(new RadioButtonRendererHigh(rb));
            rb.setBounds(0, 0, 100, 100);
            rb.setModelColumn(COL_VALUE);
            return rb;
        }

        static ProgressBarController progress() {
            ProgressBarController pb = new ProgressBarController();
            pb.setRenderer(new ProgressBarRendererHigh(pb));
            pb.setBitmaps(new int[] {40, 41, 42, 43, 44, 45});
            pb.setColorIndices(new int[] {1, 2, 4, 0});
            pb.setMaximumModelValue(100);
            pb.setMinimumModelValue(0);
            pb.setModelColumn(COL_VALUE);
            pb.setPreferredHeight(15);
            return pb;
        }

        static IconController icon() {
            IconController ic = new IconController();
            IconRendererHigh r = new IconRendererHigh(ic);
            ic.setRenderer(r);
            ic.setBitmaps(ICON_CELL_BITMAPS);
            ic.setModelColumn(COL_VALUE);
            ic.setPreferredHeight(1);
            r.setAlignment(1, 5);
            return ic;
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
