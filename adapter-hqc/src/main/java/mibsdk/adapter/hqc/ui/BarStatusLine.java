package mibsdk.adapter.hqc.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.view.Screen;
import de.audi.tghu.car.hmi.evohigh.FontFactory;
import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import de.esolutions.hmi.widgets.audi.base.HMITerminalImpl;
import de.esolutions.hmi.widgets.audi.base.IStatusbarGapEventHandler;
import de.esolutions.hmi.widgets.audi.base.WidgetRegistry;
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
 * the row at y=0 shows. A wrap is the same one-row move (the ring makes the last row's neighbour the first). The config
 * is re-read before each dwell, so a settings change applies without a restart.
 */
final class BarStatusLine implements StatusLineSink {

    private static final String CONFIG = "/mnt/persist/mibsdk/statusbar.properties";
    private static final int WIDTH = 200;
    private static final int HEIGHT = 24;
    private static final int ICON_X = 0;
    private static final int ICON_Y = 2;
    private static final int LABEL_X = 24;
    private static final int LABEL_W = WIDTH - LABEL_X;
    /** One animation frame; a 300 ms slide is 18 of them. */
    static final int FRAME_MS = 16;

    private final Object frameworkAccess;
    private final HmiPoster poster;
    private final RingLog log;
    private final IconIds ids;
    private final BuiltinIcons builtins;

    private volatile boolean attached;
    private volatile boolean visible = true;
    private volatile List<UiStatusPart> pending;
    /** HMIEvent, once the bar is attached: the caller hides the popups the bar took over from. */
    private volatile Runnable onAttached;
    /** The last attach failure, so a retried attach logs only when the reason changes. HMIEvent only. */
    private String attachFailure;
    /** Bench diagnostics: log the line only when its shape changes (items, file models). HMIEvent only. */
    private int lastItems = -1;
    /** Pager diagnostics: the last pageCount logged. Pager thread only. */
    private int lastPagerCount = -1;
    /** The rows in the line now; read by the pager thread for the wrap and the "nothing to page" check. */
    private volatile int pageCount;
    /** The row at y=0; HMIEvent only. */
    private int page;
    /** The ring's offset during a slide, 0..{@link #HEIGHT}; HMIEvent only. */
    private int slidePx;
    /** Bumped to cancel the frames of a slide (a new line, or a hide); read and written on both threads. */
    private volatile int slideGen;
    private LayoutContainerController viewport;
    private LayoutContainerController group;
    private final List<Row> rows = new ArrayList<Row>();

    private static final class Row {
        LabelController label;
        ColourLabelRenderer renderer;
        IconController icon;
        IconController fileIcon;
        int lastFileId = -1;
    }

    BarStatusLine(Object frameworkAccess, HmiPoster poster, RingLog log, IconIds ids, BuiltinIcons builtins) {
        this.frameworkAccess = frameworkAccess;
        this.poster = poster;
        this.log = log;
        this.ids = ids;
        this.builtins = builtins;
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
                layoutRows();
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
            viewport = new LayoutContainerController();
            CompositeRendererHigh renderer = new CompositeRendererHigh(viewport);
            renderer.setClipping(true);
            viewport.setRenderer(renderer);
            int x = Math.max(0, w - WIDTH);
            viewport.setBounds(x, 6, WIDTH, HEIGHT);
            group.add(viewport);
            group.setCompositesDirty(true);
            attached = true;
            attachFailure = null;
            if (pending != null) {
                update();
            }
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

    /** HMIEvent: rebuild the row texts/icons/colours from the ordered parts. */
    private void update() {
        List<UiStatusPart> parts = pending;
        StatusLayout l = StatusLayout.of(parts, ids, builtins, true);
        List<Item> items = new ArrayList<Item>();
        add(items, l.indicators());
        for (int i = 0; i < HqcUiIds.STATUS_CELLS; i++) {
            add(items, l.cell(i));
        }
        ensureRows(items.size());
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean used = i < items.size();
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
        pageCount = items.size();
        if (items.size() != lastItems) {
            lastItems = items.size();
            log("line: items=" + items.size());
        }
        if (page >= pageCount) {
            page = 0;
        }
        // Do not touch slidePx/slideGen here: a status update often arrives during the 300 ms slide (a plugin updates
        // its text), and cancelling the slide on every update meant the page never advanced on the bench. The rows are
        // rebuilt in place, so the frames already posted keep animating the same ring; hide() still cancels them.
        layoutRows();
    }

    private void add(List<Item> out, StatusLayout.Cell c) {
        if (c != null && c.text != null && c.text.length() > 0) {
            out.add(new Item(c.text, c.stockIconId, c.fileIconId, c.filePath, c.ownColours, c.colourArgb));
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
            r.renderer.setAlignment(1, 4); // ALIGN_LEFT like the popup's labels; the default is right-aligned
            r.label.setColorIndices(new int[] {1, 2, 4, 0});
            r.icon = new IconController();
            IconRendererHigh ir = new IconRendererHigh(r.icon);
            r.icon.setRenderer(ir);
            r.icon.setProcessStateChange(2); // as the popup's icons, for the renderer's state pass
            r.icon.setBounds(ICON_X, ICON_Y, HqcUiIds.STATUS_ICON_PX, HqcUiIds.STATUS_ICON_PX);
            r.fileIcon = new IconController();
            IconRendererHigh fr = new IconRendererHigh(r.fileIcon);
            r.fileIcon.setRenderer(fr);
            r.fileIcon.setProcessStateChange(2);
            r.fileIcon.setBounds(ICON_X, ICON_Y, HqcUiIds.STATUS_ICON_PX, HqcUiIds.STATUS_ICON_PX);
            viewport.add(r.icon);
            viewport.add(r.fileIcon); // above the id icon, as in the popup
            viewport.add(r.label);
            rows.add(r);
        }
    }

    /**
     * HMIEvent: place the ring. Row {@code i} sits {@code (i - page) mod n} rows down, less the slide's offset, so only
     * the row at y=0 is inside the clipped viewport. Hidden or unused rows are invisible, not moved off the ring.
     */
    private void layoutRows() {
        if (viewport == null) {
            return;
        }
        boolean on = visible;
        int n = pageCount;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean used = on && i < n;
            r.label.setVisible(used);
            r.icon.setVisible(used);
            r.fileIcon.setVisible(used);
            int y = 0;
            if (used) {
                int slot = i - page;
                if (slot < 0) {
                    slot += n;
                }
                y = slot * HEIGHT - slidePx;
            }
            r.label.setBounds(LABEL_X, y, LABEL_W, HEIGHT);
            r.icon.setBounds(ICON_X, y + ICON_Y, HqcUiIds.STATUS_ICON_PX, HqcUiIds.STATUS_ICON_PX);
            r.fileIcon.setBounds(ICON_X, y + ICON_Y, HqcUiIds.STATUS_ICON_PX, HqcUiIds.STATUS_ICON_PX);
            r.label.setCompositesDirty(true);
            r.icon.setCompositesDirty(true);
            r.fileIcon.setCompositesDirty(true);
        }
        viewport.setCompositesDirty(true);
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
                    StatusBarConfig config = StatusBarConfig.load(new File(CONFIG));
                    if (!sleep(config.dwellMs())) {
                        return;
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
                        while (attached && poster.pending()) {
                            if (!sleep(2)) {
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
