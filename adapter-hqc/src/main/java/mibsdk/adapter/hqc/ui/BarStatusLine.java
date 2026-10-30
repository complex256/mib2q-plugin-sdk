package mibsdk.adapter.hqc.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.view.Screen;
import de.audi.tghu.car.hmi.evohigh.FontFactory;
import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import de.esolutions.hmi.widgets.audi.base.HMITerminalImpl;
import de.esolutions.hmi.widgets.audi.base.IStatusbarGapEventHandler;
import de.esolutions.hmi.widgets.audi.base.WidgetRegistry;
import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.evo.high.RedrawContextHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.CompositeRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.IconRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.LabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.IconController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LayoutContainerController;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import mibsdk.platform.UiStatusPart;
import mibsdk.runtime.RingLog;
import mibsdk.runtime.StatusBarConfig;

/**
 * Renders the SDK status line in the built-in car status bar (right group), one item per row in a clipped viewport. It
 * attaches lazily on HMIEvent through the terminal's {@link WidgetRegistry}; before the bar exists it throws, so the
 * caller can fall back to popup 609600 / popup 65.
 *
 * <p>Rows come from {@link StatusLayout} (the same cells the popup uses), so indicators and plugin items keep their
 * texts and icons. A built-in icon is drawn by its Car image id; a plugin's PNG is drawn by its image id from the one
 * {@link IconIds} table, which {@link SdkSystemScreenFactory#getImagePath} serves too, so its path is global instead of
 * Car-only (a {@code ResourceLocatorModel} does not paint in a plain layout container: only a popup registers a model
 * view). A row's text colour is the item's {@code UiColor} when it set one, else the bar's own (white/app palette),
 * through {@link ColourLabelRenderer}.
 *
 * <p>Pagination: the rows sit on a ring one {@link #HEIGHT} apart, and every {@link StatusBarConfig#dwellMs()} the ring
 * moves one row up in {@link StatusBarConfig#animateMs()} of {@value #FRAME_MS} ms frames; the viewport clips, so only
 * the row at y=0 shows. A wrap is the same one-row move (the ring makes the last row's neighbour the first). A text
 * item is one row; icon-only items share right-aligned icon rows, chunked by {@link #ICON_ROW_CAPACITY} and ordered by
 * the user's priority. When the stock bar has a visible right-side icon, its group is page 0 and the two sides
 * crossfade. The config is re-read before each dwell, so a settings change applies without a restart.
 */
final class BarStatusLine implements StatusLineSink {

    private static final String CONFIG = "/mnt/persist/mibsdk/statusbar.properties";
    private static final int WIDTH = 200;
    private static final int HEIGHT = 30;
    private static final int ICON_X = 0;
    private static final int ICON_Y = 0;
    private static final int ICON_PX = 30;
    private static final int LABEL_X = 34;
    private static final int LABEL_W = WIDTH - LABEL_X;
    /** One icon slot in a shared icon row: 30 px + a 2 px gap. */
    private static final int ICON_PITCH = ICON_PX + 2;
    /** How many icon-only items fit in one right-aligned shared row. */
    static final int ICON_ROW_CAPACITY = WIDTH / ICON_PITCH;
    /** One animation frame; a 300 ms slide is 18 of them. */
    static final int FRAME_MS = 16;
    /**
     * The bar poster's pending deadline: much shorter than the applier's 5 s. A frame the HMI dropped is superseded by
     * the next post after this long instead of blocking the slide (the pager waits for each frame to run).
     */
    static final long POSTER_DEADLINE_MS = 250;

    private final Object frameworkAccess;
    private final HmiPoster poster;
    private final RingLog log;
    private final IconIds ids;
    private final BuiltinIcons builtins;
    /** The settings file: enabled/dwell/animation, re-read when it changes (the runtime writes it on a user action). */
    private final File configFile;
    private StatusBarConfig config; // guarded by this
    private long configStamp = -1; // the file's lastModified of config; guarded by this

    private volatile boolean attached;
    private volatile boolean visible = true;
    private volatile List<UiStatusPart> pending;
    /** HMIEvent, once the bar is attached: the caller hides the popups the bar took over from. */
    private volatile Runnable onAttached;
    /** The status bar handler; its currentHMIContext field is the HMI context (model 138). Set on HMIEvent. */
    private volatile Object handler;
    private Field contextField; // HMIEvent only
    /** The last context the rows were built for; the pager rebuilds on HMIEvent when it moves. */
    private int lastContext = Integer.MIN_VALUE;
    /** True when the stock bar has a visible right-side icon: page 0 shows the stock group. HMIEvent only. */
    private volatile boolean stockPage;
    /** True while the stock group is the one showing (its icons are visible, the SDK viewport is hidden). */
    private boolean stockShown;
    private Method stockRestore; // doRightGroupPriorisation; HMIEvent only
    private Method stockHide; // setAllIconVisibility(boolean); HMIEvent only
    private String stockFailure;
    private boolean lastStockPage;
    /** The right edge and vertical bounds of the visible stock icons, in the right group's coordinates; -1 for none. */
    private int stockRight = -1;
    private int stockY = -1;
    /** True while the stock group and the SDK viewport crossfade; HMIEvent only. */
    private volatile boolean stockFading;
    /** Bench diagnostics: the icon rows' shape. HMIEvent only. */
    private int lastIcons = -1;
    /** The last attach failure, so a retried attach logs only when the reason changes. HMIEvent only. */
    private String attachFailure;
    /** Bench diagnostics: log the line only when its shape changes (items, file models). HMIEvent only. */
    private int lastItems = -1;
    /** Pager diagnostics: the last pageCount logged. Pager thread only. */
    private int lastPagerCount = -1;
    /** The rows in the line now; read by the pager thread for the wrap and the "nothing to page" check. */
    private volatile int pageCount;
    /** The row at y=0; HMIEvent only. */
    private volatile int page;
    /** The ring's offset during a slide, 0..{@link #HEIGHT}; HMIEvent only. */
    private int slidePx;
    /** Bumped to cancel the frames of a slide (a new line, or a hide); read and written on both threads. */
    private volatile int slideGen;
    private LayoutContainerController viewport;
    private LayoutContainerController group;
    private final List<Row> rows = new ArrayList<Row>();
    private final List<IconSlot> slots = new ArrayList<IconSlot>();
    /** The rows in ring order (text rows and shared icon pages); HMIEvent only. */
    private List<RowSpec> rowSpecs = new ArrayList<RowSpec>();

    /** One icon-only item's slot in a shared icon row (no label). */
    private static final class IconSlot {
        IconController icon;
        IconController fileIcon;
        boolean used;
        int lastFileId = -1;
    }

    private static final class Row {
        LabelController label;
        ColourLabelRenderer renderer;
        IconController icon;
        IconController fileIcon;
        boolean used;
        int lastFileId = -1;
    }

    /** One row of the ring: a text row, a page of icon-only items, or the stock bar's own icon row. */
    static final class RowSpec {
        static final int TEXT = 0;
        static final int ICONS = 1;
        static final int STOCK = 2;
        final int kind;
        /** The source index: the cell this row starts at (the indicator is -1, the stock row -2). */
        final int rank;
        /** For TEXT: the index in the text-row list. */
        final int textIndex;
        /** For ICONS: the first slot index and how many slots the page uses. */
        final int iconStart;
        final int iconCount;

        private RowSpec(int kind, int rank, int textIndex, int iconStart, int iconCount) {
            this.kind = kind;
            this.rank = rank;
            this.textIndex = textIndex;
            this.iconStart = iconStart;
            this.iconCount = iconCount;
        }

        static RowSpec text(int index, int rank) {
            return new RowSpec(TEXT, rank, index, -1, 0);
        }

        static RowSpec icons(int start, int count, int rank) {
            return new RowSpec(ICONS, rank, -1, start, count);
        }

        static RowSpec stock() {
            return new RowSpec(STOCK, -2, -1, -1, 0);
        }
    }

    BarStatusLine(Object frameworkAccess, HmiPoster poster, RingLog log, IconIds ids, BuiltinIcons builtins) {
        this(frameworkAccess, poster, log, ids, builtins, new File(CONFIG));
    }

    BarStatusLine(Object frameworkAccess, HmiPoster poster, RingLog log, IconIds ids, BuiltinIcons builtins,
            File configFile) {
        this.frameworkAccess = frameworkAccess;
        this.poster = poster;
        this.log = log;
        this.ids = ids;
        this.builtins = builtins;
        this.configFile = configFile == null ? new File(CONFIG) : configFile;
    }

    /** The current settings; the file is re-read only when its mtime moves (a user action in the SDK page). */
    synchronized StatusBarConfig configNow() {
        // mtime and length: a same-second rewrite (a toggle, a new dwell) still changes the length.
        long stamp = configFile.lastModified() * 31L + configFile.length();
        if (config == null || stamp != configStamp) {
            config = StatusBarConfig.load(configFile);
            configStamp = stamp;
        }
        return config;
    }

    /** The user's "Status bar" switch; the caller falls back to the popup when false. */
    boolean enabled() {
        return configNow().enabled();
    }

    public boolean available() {
        return attached;
    }

    /** Set before the first show: runs on HMIEvent right after the bar attaches (and has drawn the pending line). */
    void setOnAttached(Runnable r) {
        this.onAttached = r;
    }

    public void show(List<UiStatusPart> parts, String textLine) {
        pending = parts;
        visible = true;
        if (!attached) {
            poster.post(new Runnable() {
                public void run() {
                    attach();
                }
            });
            throw new IllegalStateException("the status bar is not attached yet");
        }
        poster.post(new Runnable() {
            public void run() {
                update();
            }
        });
    }

    public void hide() {
        visible = false;
        slideGen++;
        poster.post(new Runnable() {
            public void run() {
                stockFading = false;
                layoutRows();
                applyStockVisibility(true);
            }
        });
    }

    /** HMIEvent: find the global bar, make the clipped viewport and the rows it needs. Retried on every show until it works. */
    private void attach() {
        if (attached) {
            return;
        }
        try {
            IFrameworkAccess fw = (IFrameworkAccess) frameworkAccess;
            HMIService svc = fw.getHMIService();
            if (svc == null) {
                fail("no HMI service");
                return;
            }
            Screen screen = svc.getScreenManager(0) == null ? null
                    : svc.getScreenManager(0).getCurrentConnectedScreen();
            if (!(screen instanceof AbstractWidget)) {
                fail("current screen is not a widget: " + screen);
                return;
            }
            HMITerminalImpl terminal = (HMITerminalImpl) ((AbstractWidget) screen).getTerminal();
            if (terminal == null) {
                fail("no terminal on " + screen.getClass().getName());
                return;
            }
            WidgetRegistry registry = terminal.getWidgetRegistry();
            if (registry == null) {
                fail("no widget registry");
                return;
            }
            IStatusbarGapEventHandler handler = registry.getStatusbarGapEventHandler();
            if (handler == null) {
                fail("no status bar handler");
                return;
            }
            this.handler = handler;
            Field field = findField(handler.getClass(), "rightGroupContainer");
            if (field == null) {
                fail("no rightGroupContainer field");
                return;
            }
            field.setAccessible(true);
            Object c = field.get(handler);
            if (!(c instanceof LayoutContainerController)) {
                fail("rightGroup is not a LayoutContainerController: " + c);
                return;
            }
            group = (LayoutContainerController) c;
            int w = group.getWidth() > 0 ? group.getWidth() : 262;
            int h = group.getHeight() > 0 ? group.getHeight() : 36;
            viewport = new LayoutContainerController();
            CompositeRendererHigh renderer = new CompositeRendererHigh(viewport);
            renderer.setClipping(true);
            viewport.setRenderer(renderer);
            viewport.setBounds(viewportX(w), 0, WIDTH, HEIGHT);
            group.add(viewport);
            group.setCompositesDirty(true);
            attached = true;
            attachFailure = null;
            if (pending != null) {
                update();
            }
            lastContext = currentContext();
            Runnable r = onAttached;
            if (r != null) {
                try {
                    r.run();
                } catch (Throwable t) {
                    log("attach callback failed: " + t);
                }
            }
            startPager();
            log("attached: rightGroup " + w + "x" + group.getHeight());
        } catch (Throwable t) {
            fail("attach failed: " + t);
        }
    }

    private void fail(String reason) {
        if (!reason.equals(attachFailure)) {
            attachFailure = reason;
            log(reason);
        }
    }

    /**
     * HMIEvent: ask the stock controller for its natural visibility, then note whether any right-side stock icon (or the
     * provider name) is visible. Leaves the stock group in its natural state; {@link #applyStockVisibility} decides
     * whether to hide it for the current page.
     */
    private boolean detectStockIcons() {
        if (group == null || handler == null) {
            return false;
        }
        try {
            if (stockRestore == null) {
                stockRestore = handler.getClass().getMethod("doRightGroupPriorisation");
            }
            stockRestore.invoke(handler);
            stockRight = -1;
            stockY = -1;
            List<?> children = group.getChildren();
            for (int i = 0; i < children.size(); i++) {
                Object o = children.get(i);
                if (o instanceof AbstractWidgetController && o != viewport) {
                    AbstractWidgetController w = (AbstractWidgetController) o;
                    // The stock controller hides a cell with setOnScreen(false); isVisible() alone stays true.
                    if (w.isVisible() && w.isOnScreen() && w.getPreferredWidth() > 0) {
                        if (w.getX() + w.getWidth() > stockRight) {
                            stockRight = w.getX() + w.getWidth();
                        }
                        if (w instanceof IconController && w.getWidth() > 0
                                && (stockY < 0 || w.getY() < stockY)) {
                            stockY = w.getY();
                        }
                    }
                }
            }
            return stockRight >= 0;
        } catch (Throwable t) {
            stockFail("detect: " + t);
            return false;
        }
    }

    /**
     * HMIEvent: show either the stock group's own row or the SDK viewport. While the SDK shows a custom row the stock
     * icons are hidden again (the stock controller re-shows them on model updates); on the stock page (or while the SDK
     * bar is off) the stock controller restores its per-context visibility and the viewport is hidden.
     */
    private void applyStockVisibility(boolean force) {
        if (viewport == null) {
            return;
        }
        boolean stockNow = visible && stockPage && page == 0;
        boolean showStock = stockNow || !visible;
        if (showStock) {
            if (force || !stockShown) {
                restoreStockIcons();
            }
            stockShown = true;
            viewport.setVisible(false);
        } else {
            if (force || stockShown) {
                hideStockIcons();
            }
            stockShown = false;
            viewport.setVisible(true);
        }
    }

    private void restoreStockIcons() {
        if (handler == null) {
            return;
        }
        try {
            if (stockRestore == null) {
                stockRestore = handler.getClass().getMethod("doRightGroupPriorisation");
            }
            stockRestore.invoke(handler);
            if (group != null) {
                group.setCompositesDirty(true);
            }
            setStockOpacity(1f);
        } catch (Throwable t) {
            stockFail("restore: " + t);
        }
    }

    private void hideStockIcons() {
        if (handler == null) {
            return;
        }
        try {
            if (stockHide == null) {
                stockHide = handler.getClass().getMethod("setAllIconVisibility", boolean.class);
            }
            stockHide.invoke(handler, false);
            if (group != null) {
                group.setCompositesDirty(true);
            }
            // Pin them transparent too: a stock model update can call setOnScreen(true) again mid-page, and the
            // visibility recompute does not touch opacity.
            setStockOpacity(0f);
        } catch (Throwable t) {
            stockFail("hide: " + t);
        }
    }

    private void stockFail(String reason) {
        if (!reason.equals(stockFailure)) {
            stockFailure = reason;
            log(reason);
        }
    }

    private void updateStockPageState() {
        boolean hadStock = stockPage;
        stockPage = detectStockIcons();
        if (stockPage != hadStock) {
            page = pageAfterStockChange(page, hadStock, stockPage);
        }
        if (stockPage != lastStockPage) {
            lastStockPage = stockPage;
            log("stock row " + (stockPage ? "on" : "off") + " right=" + stockRight + " y=" + stockY);
        }
    }

    /** HMIEvent: rebuild the row texts/icons/colours from the ordered parts, for the current HMI context. */
    private void update() {
        updateStockPageState();
        List<UiStatusPart> parts = visibleParts(pending, currentContext());
        StatusLayout l = StatusLayout.of(parts, ids, builtins, true);
        List<Item> icons = new ArrayList<Item>();
        List<Item> items = new ArrayList<Item>();
        List<Integer> itemRanks = new ArrayList<Integer>();
        List<Integer> iconRanks = new ArrayList<Integer>();
        add(items, itemRanks, l.indicators(), -1);
        for (int i = 0; i < HqcUiIds.STATUS_CELLS; i++) {
            StatusLayout.Cell c = l.cell(i);
            add(items, itemRanks, c, i);
            addIcon(icons, iconRanks, c, i);
        }
        ensureRows(items.size());
        ensureSlots(icons.size());
        for (int i = 0; i < slots.size(); i++) {
            IconSlot s = slots.get(i);
            Item it = i < icons.size() ? icons.get(i) : null;
            s.used = it != null;
            s.icon.setBitmaps(it != null && it.stockIconId >= 0 ? new int[] {it.stockIconId} : new int[0]);
            s.icon.setColorIndices(it != null && it.ownColours ? HqcUiIds.STATUS_ICON_OWN_COLOURS : null);
            s.fileIcon.setBitmaps(it != null && it.fileIconId >= 0 ? new int[] {it.fileIconId} : new int[0]);
            s.fileIcon.setColorIndices(it != null && it.ownColours ? HqcUiIds.STATUS_ICON_OWN_COLOURS : null);
            int fid = it == null ? -1 : it.fileIconId;
            if (fid != s.lastFileId) {
                s.lastFileId = fid;
                log("icon row slot " + i + " id " + fid);
            }
            s.icon.setCompositesDirty(true);
            s.fileIcon.setCompositesDirty(true);
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean used = i < items.size();
            r.used = used;
            Item it = used ? items.get(i) : null;
            r.label.setText(used ? it.text : "");
            r.icon.setBitmaps(used && it.stockIconId >= 0 ? new int[] {it.stockIconId} : new int[0]);
            r.icon.setColorIndices(used && it.ownColours ? HqcUiIds.STATUS_ICON_OWN_COLOURS : null);
            r.fileIcon.setBitmaps(used && it.fileIconId >= 0 ? new int[] {it.fileIconId} : new int[0]);
            r.fileIcon.setColorIndices(used && it.ownColours ? HqcUiIds.STATUS_ICON_OWN_COLOURS : null);
            r.renderer.setColour(used ? it.colourArgb : -1L);
            int fid = used ? it.fileIconId : -1;
            if (fid != r.lastFileId) {
                r.lastFileId = fid;
                log("file icon row " + i + " id " + fid);
            }
            r.label.setCompositesDirty(true);
            r.icon.setCompositesDirty(true);
            r.fileIcon.setCompositesDirty(true);
        }
        rowSpecs = arrange(itemRanks, iconRanks);
        if (stockPage) {
            rowSpecs.add(0, RowSpec.stock());
        }
        pageCount = rowSpecs.size();
        if (items.size() != lastItems || icons.size() != lastIcons) {
            lastItems = items.size();
            lastIcons = icons.size();
            log("line: items=" + items.size() + " icons=" + icons.size());
        }
        if (page >= pageCount) {
            page = 0;
        }
        // Do not touch slidePx/slideGen here: a status update often arrives during the 300 ms slide (a plugin updates
        // its text), and cancelling the slide on every update meant the page never advanced on the bench. The rows are
        // rebuilt in place, so the frames already posted keep animating the same ring; hide() still cancels them.
        layoutRows();
        if (!stockFading) {
            applyStockVisibility(true);
        }
    }

    private void add(List<Item> out, List<Integer> ranks, StatusLayout.Cell c, int rank) {
        if (c != null && c.text != null && c.text.length() > 0) {
            out.add(new Item(c.text, c.stockIconId, c.fileIconId, c.filePath, c.ownColours, c.colourArgb));
            ranks.add(rank);
        }
    }

    /** An icon-only cell (no text, an icon): a shared icon row, not a text row. */
    private void addIcon(List<Item> out, List<Integer> ranks, StatusLayout.Cell c, int rank) {
        if (c != null && (c.text == null || c.text.length() == 0)
                && (c.stockIconId >= 0 || c.fileIconId >= 0)) {
            out.add(new Item(c.text, c.stockIconId, c.fileIconId, c.filePath, c.ownColours, c.colourArgb));
            ranks.add(rank);
        }
    }

    /**
     * The parts that show in {@code context} (a {@code HmiContext} value, or -1 before the stub pushed it): the runtime's
     * indicator always, an item when its {@code visibleIn} mask is empty or has this context.
     */
    static List<UiStatusPart> visibleParts(List<UiStatusPart> parts, int context) {
        if (parts == null || context < 0) {
            return parts; // unknown context: do not hide anything
        }
        List<UiStatusPart> out = new ArrayList<UiStatusPart>();
        for (int i = 0; i < parts.size(); i++) {
            UiStatusPart p = parts.get(i);
            if (p == null || p.indicator() || p.showsIn(context)) {
                out.add(p);
            }
        }
        return out;
    }

    /** The bar's current HMI context (its handler's currentHMIContext), or -1 before it is known. */
    private int currentContext() {
        Object h = handler;
        if (h == null) {
            return -1;
        }
        try {
            if (contextField == null) {
                contextField = findField(h.getClass(), "currentHMIContext");
                if (contextField != null) {
                    contextField.setAccessible(true);
                }
            }
            if (contextField == null) {
                return -1;
            }
            Object v = contextField.get(h);
            return v instanceof Integer ? ((Integer) v).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private void ensureRows(int n) {
        while (rows.size() < n) {
            Row r = new Row();
            r.label = new LabelController();
            r.renderer = new ColourLabelRenderer(r.label);
            r.label.setRenderer(r.renderer);
            r.renderer.setFonts(FontFactory.getFonts(0, 0, (IFrameworkAccess) frameworkAccess));
            r.label.setBounds(LABEL_X, 0, LABEL_W, HEIGHT);
            r.renderer.setAlignment(1, 5); // ALIGN_LEFT, vertically centred in the taller row
            r.label.setColorIndices(new int[] {1, 2, 4, 0});
            r.icon = new IconController();
            IconRendererHigh ir = new IconRendererHigh(r.icon);
            configureIconRenderer(ir);
            r.icon.setRenderer(ir);
            r.icon.setProcessStateChange(2); // as the popup's icons, for the renderer's state pass
            r.icon.setBounds(ICON_X, ICON_Y, ICON_PX, ICON_PX);
            r.fileIcon = new IconController();
            IconRendererHigh fr = new IconRendererHigh(r.fileIcon);
            configureIconRenderer(fr);
            r.fileIcon.setRenderer(fr);
            r.fileIcon.setProcessStateChange(2);
            r.fileIcon.setBounds(ICON_X, ICON_Y, ICON_PX, ICON_PX);
            viewport.add(r.icon);
            viewport.add(r.fileIcon); // above the id icon, as in the popup
            viewport.add(r.label);
            rows.add(r);
        }
    }

    private void ensureSlots(int n) {
        while (slots.size() < n) {
            IconSlot s = new IconSlot();
            s.icon = new IconController();
            IconRendererHigh ir = new IconRendererHigh(s.icon);
            configureIconRenderer(ir);
            s.icon.setRenderer(ir);
            s.icon.setProcessStateChange(2);
            s.icon.setBounds(0, ICON_Y, ICON_PX, ICON_PX);
            s.fileIcon = new IconController();
            IconRendererHigh fr = new IconRendererHigh(s.fileIcon);
            configureIconRenderer(fr);
            s.fileIcon.setRenderer(fr);
            s.fileIcon.setProcessStateChange(2);
            s.fileIcon.setBounds(0, ICON_Y, ICON_PX, ICON_PX);
            viewport.add(s.icon);
            viewport.add(s.fileIcon);
            slots.add(s);
        }
    }

    /** Fits the icon into the 30 px slot: a 30 px asset stays 1:1, a legacy 20 px one is upscaled. */
    private static void configureIconRenderer(IconRendererHigh r) {
        r.setScaleMode(1);
        r.setAlignment(2, 5);
    }

    static int iconRowCapacity() {
        return ICON_ROW_CAPACITY;
    }

    /** The viewport's right-group x: flush with its right edge. */
    static int viewportX(int groupWidth) {
        return Math.max(0, groupWidth - WIDTH);
    }

    /** The viewport's x, anchored to the visible stock icons' right edge when there are any. */
    static int anchoredViewportX(int stockRight, int groupWidth) {
        return stockRight > 0 ? Math.max(0, stockRight - WIDTH) : viewportX(groupWidth);
    }

    /**
     * The viewport's y: the stock icon cell's top when there is one, otherwise the same top. The native status
     * icons sit at the top of the 36 px group; using the group centre when no stock icon is visible made the SDK
     * row jump by a few pixels between contexts.
     */
    static int anchoredViewportY(int stockY) {
        return Math.max(0, stockY);
    }

    /**
     * Keeps the same custom row visible when the stock page appears or disappears: the stock page is index 0, so the
     * custom rows shift by one.
     */
    static int pageAfterStockChange(int page, boolean hadStock, boolean hasStock) {
        if (hadStock == hasStock) {
            return page;
        }
        if (hasStock) {
            return page + 1;
        }
        return page > 0 ? page - 1 : 0;
    }

    /** Where icon {@code index} of a right-aligned row of {@code count} sits; the last one ends at {@link #WIDTH}. */
    static int iconX(int index, int count) {
        return WIDTH - (ICON_PX + (count - 1 - index) * ICON_PITCH);
    }

    /** How many shared icon rows {@code icons} icon-only items need. */
    static int iconPageCount(int icons) {
        return (icons + ICON_ROW_CAPACITY - 1) / ICON_ROW_CAPACITY;
    }

    /** Where a text row's label starts. */
    static int textLabelX() {
        return LABEL_X;
    }

    /** A text row's label width: the whole viewport right of the icon column. */
    static int textLabelW() {
        return LABEL_W;
    }

    /** The y of ring row {@code row} while {@code page} shows, before the slide offset. */
    static int ringY(int row, int page, int rows) {
        if (rows <= 0) {
            return 0;
        }
        int slot = row - page;
        if (slot < 0) {
            slot += rows;
        }
        return slot * HEIGHT;
    }

    /**
     * Orders the text rows and shared icon pages by their first source index. Icon-only items are chunked into
     * pages of {@link #ICON_ROW_CAPACITY}, highest priority first.
     */
    static List<RowSpec> arrange(List<Integer> textRanks, List<Integer> iconRanks) {
        List<RowSpec> out = new ArrayList<RowSpec>();
        for (int i = 0; i < textRanks.size(); i++) {
            out.add(RowSpec.text(i, textRanks.get(i)));
        }
        for (int start = 0; start < iconRanks.size(); start += ICON_ROW_CAPACITY) {
            int count = Math.min(ICON_ROW_CAPACITY, iconRanks.size() - start);
            out.add(RowSpec.icons(start, count, iconRanks.get(start)));
        }
        Collections.sort(out, new Comparator<RowSpec>() {
            public int compare(RowSpec a, RowSpec b) {
                return a.rank < b.rank ? -1 : a.rank > b.rank ? 1 : 0;
            }
        });
        return out;
    }

    /**
     * HMIEvent: place the ring. Row {@code i} sits {@code (i - page) mod n} rows down, less the slide's offset, so only
     * the row at y=0 is inside the clipped viewport. Hidden or unused rows are invisible, not moved off the ring. Text
     * rows use the whole viewport; a shared icon row is right-aligned, its highest-priority icon leftmost.
     */
    private void layoutRows() {
        if (viewport == null) {
            return;
        }
        boolean on = visible;
        if (group != null) {
            int w = group.getWidth() > 0 ? group.getWidth() : 262;
            viewport.setBounds(anchoredViewportX(stockRight, w), anchoredViewportY(stockY), WIDTH, HEIGHT);
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            r.label.setVisible(false);
            r.icon.setVisible(false);
            r.fileIcon.setVisible(false);
        }
        for (int i = 0; i < slots.size(); i++) {
            IconSlot s = slots.get(i);
            s.icon.setVisible(false);
            s.fileIcon.setVisible(false);
        }
        int n = rowSpecs.size();
        for (int r = 0; r < n; r++) {
            RowSpec spec = rowSpecs.get(r);
            int y = ringY(r, page, n) - slidePx;
            if (spec.kind == RowSpec.TEXT) {
                Row row = rows.get(spec.textIndex);
                boolean used = on && row.used;
                row.label.setVisible(used);
                row.icon.setVisible(used);
                row.fileIcon.setVisible(used);
                row.label.setBounds(LABEL_X, y, LABEL_W, HEIGHT);
                row.icon.setBounds(ICON_X, y + ICON_Y, ICON_PX, ICON_PX);
                row.fileIcon.setBounds(ICON_X, y + ICON_Y, ICON_PX, ICON_PX);
                row.label.setCompositesDirty(true);
                row.icon.setCompositesDirty(true);
                row.fileIcon.setCompositesDirty(true);
            } else if (spec.kind == RowSpec.ICONS) {
                for (int j = 0; j < spec.iconCount; j++) {
                    IconSlot s = slots.get(spec.iconStart + j);
                    boolean show = on && s.used;
                    s.icon.setVisible(show);
                    s.fileIcon.setVisible(show);
                    int x = iconX(j, spec.iconCount);
                    s.icon.setBounds(x, y + ICON_Y, ICON_PX, ICON_PX);
                    s.fileIcon.setBounds(x, y + ICON_Y, ICON_PX, ICON_PX);
                    s.icon.setCompositesDirty(true);
                    s.fileIcon.setCompositesDirty(true);
                }
            }
        }
        viewport.setCompositesDirty(true);
        // Bounds and visibility changes only mark widgets dirty. The HMI paints when asked (mostly on a model update),
        // so without this the slide's intermediate positions were never drawn and the page snapped at the commit; the
        // popup's colour fix needed the same triggerRepaint. Walks up to the screen.
        viewport.triggerRepaint();
    }

    /**
     * Pager thread: crossfade between the stock group and the SDK viewport over the configured animation. Entering the
     * stock page keeps the current custom row visible until the fade ends; leaving it lays out the next custom row
     * first, so neither side goes blank.
     */
    private void crossfadeStock(final int nextPage, int animateMs, final int gen) {
        final boolean toStock = nextPage == 0;
        stockFading = true;
        if (!runOnBar(new Runnable() {
            public void run() {
                if (gen != slideGen) {
                    return;
                }
                if (toStock) {
                    restoreStockIcons();
                    setStockOpacity(0f);
                    viewport.setVisible(true);
                    viewport.setOpacity(1f);
                } else {
                    page = nextPage;
                    slidePx = 0;
                    layoutRows();
                    viewport.setVisible(true);
                    viewport.setOpacity(0f);
                    setStockOpacity(1f);
                }
            }
        }) || gen != slideGen) {
            stockFading = false;
            return;
        }
        int frames = animateMs <= 0 ? 0 : Math.max(1, animateMs / FRAME_MS);
        for (int f = 1; f <= frames; f++) {
            final float a = (float) f / frames;
            if (!runOnBar(new Runnable() {
                public void run() {
                    if (gen == slideGen) {
                        setStockOpacity(toStock ? a : 1f - a);
                        viewport.setOpacity(toStock ? 1f - a : a);
                        viewport.triggerRepaint();
                    }
                }
            }) || gen != slideGen) {
                stockFading = false;
                return;
            }
            if (!sleep(FRAME_MS)) {
                stockFading = false;
                return;
            }
        }
        runOnBar(new Runnable() {
            public void run() {
                if (gen != slideGen) {
                    stockFading = false;
                    return;
                }
                if (toStock) {
                    page = 0;
                    slidePx = 0;
                    layoutRows();
                    viewport.setVisible(false);
                    viewport.setOpacity(1f);
                    setStockOpacity(1f);
                    stockShown = true;
                } else {
                    hideStockIcons();
                    stockShown = false;
                    viewport.setOpacity(1f);
                    viewport.setVisible(true);
                }
                stockFading = false;
            }
        });
    }

    /** Pager thread: posts one job to HMIEvent and waits (bounded) for it to run. */
    private boolean runOnBar(Runnable job) {
        while (attached && !poster.post(job)) {
            if (!sleep(2)) {
                return false;
            }
        }
        for (int w = 0; w < 8 && attached && poster.pending(); w++) {
            if (!sleep(4)) {
                return false;
            }
        }
        return attached;
    }

    /** HMIEvent: sets every stock child's opacity (all but the SDK viewport). */
    private void setStockOpacity(float opacity) {
        if (group == null) {
            return;
        }
        List<?> children = group.getChildren();
        for (int i = 0; i < children.size(); i++) {
            Object o = children.get(i);
            if (o instanceof AbstractWidget && o != viewport) {
                ((AbstractWidget) o).setOpacity(opacity);
            }
        }
        group.setCompositesDirty(true);
        if (viewport != null) {
            viewport.triggerRepaint();
        }
    }

    /**
     * Waits one dwell, then slides one row up over the configured animation. The config is re-read every pass, so a
     * change on the SDK page applies at the next dwell. The frames are posted to HMIEvent; a newer line or a hide bumps
     * {@link #slideGen} and the stale frames do nothing.
     */
    private void startPager() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (attached) {
                    StatusBarConfig config = configNow();
                    if (!sleep(config.dwellMs())) {
                        return;
                    }
                    final int context = currentContext();
                    if (context != lastContext) {
                        lastContext = context;
                        poster.post(new Runnable() {
                            public void run() {
                                if (pending != null) {
                                    update();
                                }
                            }
                        });
                    }
                    if (!configNow().enabled()) {
                        if (visible) {
                            visible = false;
                            slideGen++;
                            poster.post(new Runnable() {
                                public void run() {
                                    layoutRows();
                                    applyStockVisibility(true);
                                }
                            });
                        }
                        continue;
                    }
                    if (pageCount <= 1) {
                        if (pageCount != lastPagerCount) {
                            lastPagerCount = pageCount;
                            log("pager: items=" + pageCount + ", nothing to page");
                        }
                        continue;
                    }
                    if (pageCount != lastPagerCount) {
                        lastPagerCount = pageCount;
                        log("pager: items=" + pageCount);
                    }
                    final int gen = ++slideGen;
                    int animate = config.animateMs();
                    final int nextPage = (page + 1) % pageCount;
                    if (stockPage && pageCount > 1 && (page == 0 || nextPage == 0)) {
                        crossfadeStock(nextPage, animate, gen);
                        continue;
                    }
                    int frames = animate <= 0 ? 0 : Math.max(1, animate / FRAME_MS);
                    for (int f = 1; f <= frames; f++) {
                        final int px = (HEIGHT * f) / frames;
                        Runnable frame = new Runnable() {
                            public void run() {
                                if (gen == slideGen) {
                                    slidePx = px;
                                    layoutRows();
                                }
                            }
                        };
                        // Queue the frame, then wait for the HMI to apply it before the next one. Without the wait the
                        // poster coalesced them and only the final commit was seen: the page snapped.
                        while (attached && !poster.post(frame)) {
                            if (!sleep(2)) {
                                return;
                            }
                        }
                        // Bounded: a dropped frame must not hang the pager. The poster's short deadline lets the next
                        // post supersede it; 8 x 4 ms is longer than a normal frame job in practice.
                        for (int w = 0; w < 8 && attached && poster.pending(); w++) {
                            if (!sleep(4)) {
                                return;
                            }
                        }
                        if (!sleep(FRAME_MS)) {
                            return;
                        }
                    }
                    final Runnable commit = new Runnable() {
                        public void run() {
                            if (gen == slideGen && pageCount > 0) {
                                page = (page + 1) % pageCount;
                                slidePx = 0;
                                layoutRows();
                                applyStockVisibility(true);
                            }
                        }
                    };
                    // The poster drops a job while one is pending; the commit must not be lost, or the page never
                    // moves (the bench showed exactly that). Retry until it is queued or the bar goes away.
                    while (attached && !poster.post(commit)) {
                        if (!sleep(FRAME_MS)) {
                            return;
                        }
                    }
                }
            }
        }, "mibsdk-status-bar");
        t.setDaemon(true);
        t.start();
    }

    /** Sleeps {@code ms}; false when the thread was interrupted (the bar is going away). */
    private static boolean sleep(int ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private static final class Item {
        final String text;
        final int stockIconId;
        final int fileIconId;
        final String filePath;
        final boolean ownColours;
        final long colourArgb;

        Item(String text, int stockIconId, int fileIconId, String filePath, boolean ownColours, long colourArgb) {
            this.text = text;
            this.stockIconId = stockIconId;
            this.fileIconId = fileIconId;
            this.filePath = filePath;
            this.ownColours = ownColours;
            this.colourArgb = colourArgb;
        }
    }

    /**
     * A label renderer that paints its text in a fixed {@code 0xAARRGGBB} colour when one is set, else exactly as
     * {@link LabelRendererHigh} does (the palette's colour). Spike E's {@code RgbLabelRenderer}: the node's interface
     * text takes a packed {@code long}; a paint never throws into the HMI.
     */
    private static final class ColourLabelRenderer extends LabelRendererHigh {
        /** -1: no override (a packed colour is 0..0xFFFFFFFF). */
        private long colour = -1L;
        private Field nodeField;
        private Method getText;
        private Method setColor;

        ColourLabelRenderer(LabelController controller) {
            super(controller);
        }

        void setColour(long argb) {
            this.colour = argb;
        }

        @Override
        protected void applyProperties(RedrawContextHigh ctx) {
            super.applyProperties(ctx);
            if (colour < 0) {
                return;
            }
            try {
                if (nodeField == null) {
                    nodeField = LabelRendererHigh.class.getDeclaredField("node");
                    nodeField.setAccessible(true);
                }
                Object node = nodeField.get(this);
                if (node == null) {
                    return;
                }
                if (getText == null) {
                    getText = node.getClass().getMethod("getInterfaceText");
                }
                Object text = getText.invoke(node);
                if (text == null) {
                    return;
                }
                if (setColor == null) {
                    setColor = text.getClass().getMethod("setColor", long.class);
                }
                setColor.invoke(text, Long.valueOf(colour));
            } catch (Throwable t) {
                // a paint must never throw into the HMI; the text keeps the palette's colour
            }
        }
    }

    private void log(String msg) {
        if (log != null) {
            log.append("INFO", "mibsdk", "ui status bar: " + msg);
        }
    }

    private static Field findField(Class c, String name) {
        for (; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        return null;
    }
}
