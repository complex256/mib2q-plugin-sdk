package mibsdk.adapter.hqc.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.view.Screen;
import de.audi.tghu.car.hmi.evohigh.FontFactory;
import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import de.esolutions.hmi.widgets.audi.base.HMITerminalImpl;
import de.esolutions.hmi.widgets.audi.base.IStatusbarGapEventHandler;
import de.esolutions.hmi.widgets.audi.base.WidgetRegistry;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.CompositeRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.IconRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.LabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.IconController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LayoutContainerController;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import mibsdk.platform.UiStatusPart;
import mibsdk.runtime.RingLog;
import mibsdk.runtime.StatusBarConfig;

/**
 * Renders the SDK status line in the built-in car status bar (right group), one item per row in a clipped
 * viewport. It attaches lazily on HMIEvent through the terminal's {@link WidgetRegistry}; before the bar exists
 * it throws, so the caller can fall back to popup 609600 / popup 65.
 *
 * <p>Rows come from {@link StatusLayout} (the same cells the popup uses), so indicators and plugin items keep
 * their texts and built-in icons. Pagination swaps the visible row every {@link StatusBarConfig#dwellMs()}; the
 * viewport is already clipped, so the vertical slide can replace the swap later. File-backed plugin icons and
 * per-item RGBA text colours are next (the file model must be global, not the Car factory's image ids).
 */
final class BarStatusLine implements StatusLineSink {

    private static final String CONFIG = "/mnt/persist/mibsdk/statusbar.properties";
    private static final int WIDTH = 200;
    private static final int HEIGHT = 24;
    private static final int ICON_X = 0;
    private static final int LABEL_X = 24;
    private static final int LABEL_W = WIDTH - LABEL_X;

    private final Object frameworkAccess;
    private final HmiPoster poster;
    private final RingLog log;
    private final IconIds ids;
    private final BuiltinIcons builtins;
    private final StatusBarConfig config;

    private volatile boolean attached;
    private volatile boolean visible = true;
    private volatile List<UiStatusPart> pending;
    private int page;
    private LayoutContainerController viewport;
    private LayoutContainerController group;
    private final List<Row> rows = new ArrayList<Row>();

    private static final class Row {
        LabelController label;
        IconController icon;
    }

    BarStatusLine(Object frameworkAccess, HmiPoster poster, RingLog log, IconIds ids, BuiltinIcons builtins) {
        this.frameworkAccess = frameworkAccess;
        this.poster = poster;
        this.log = log;
        this.ids = ids;
        this.builtins = builtins;
        this.config = StatusBarConfig.load(new File(CONFIG));
    }

    public boolean available() {
        return attached;
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
        poster.post(new Runnable() {
            public void run() {
                showPage();
            }
        });
    }

    /** HMIEvent: find the global bar, make the clipped viewport and the rows it needs. */
    private void attach() {
        try {
            IFrameworkAccess fw = (IFrameworkAccess) frameworkAccess;
            HMIService svc = fw.getHMIService();
            Screen screen = svc.getScreenManager(0).getCurrentConnectedScreen();
            if (!(screen instanceof AbstractWidget)) {
                return;
            }
            HMITerminalImpl terminal = (HMITerminalImpl) ((AbstractWidget) screen).getTerminal();
            WidgetRegistry registry = terminal == null ? null : terminal.getWidgetRegistry();
            IStatusbarGapEventHandler handler = registry == null ? null : registry.getStatusbarGapEventHandler();
            if (handler == null) {
                return;
            }
            Field field = findField(handler.getClass(), "rightGroupContainer");
            if (field == null) {
                log("no rightGroupContainer field");
                return;
            }
            field.setAccessible(true);
            Object c = field.get(handler);
            if (!(c instanceof LayoutContainerController)) {
                log("rightGroup is not a LayoutContainerController: " + c);
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
            if (pending != null) {
                update();
            }
            startPager();
            log("attached: rightGroup " + w + "x" + group.getHeight());
        } catch (Throwable t) {
            log("attach failed: " + t);
        }
    }

    /** HMIEvent: rebuild the row texts/icons from the ordered parts. */
    private void update() {
        List<UiStatusPart> parts = pending;
        StatusLayout l = StatusLayout.of(parts, ids, builtins);
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
            r.label.setCompositesDirty(true);
            r.icon.setCompositesDirty(true);
        }
        if (page >= items.size()) {
            page = 0;
        }
        showPage();
    }

    private void add(List<Item> out, StatusLayout.Cell c) {
        if (c != null && c.text != null && c.text.length() > 0) {
            out.add(new Item(c.text, c.stockIconId, c.filePath, c.ownColours));
        }
    }

    private void ensureRows(int n) {
        while (rows.size() < n) {
            Row r = new Row();
            r.label = new LabelController();
            LabelRendererHigh lr = new LabelRendererHigh(r.label);
            r.label.setRenderer(lr);
            lr.setFonts(FontFactory.getFonts(0, 0, (IFrameworkAccess) frameworkAccess));
            r.label.setBounds(LABEL_X, 0, LABEL_W, HEIGHT);
            r.label.setColorIndices(new int[] {1, 2, 4, 0});
            r.icon = new IconController();
            IconRendererHigh ir = new IconRendererHigh(r.icon);
            r.icon.setRenderer(ir);
            r.icon.setBounds(ICON_X, 2, HqcUiIds.STATUS_ICON_PX, HqcUiIds.STATUS_ICON_PX);
            viewport.add(r.icon);
            viewport.add(r.label);
            rows.add(r);
        }
    }

    private void showPage() {
        boolean on = visible;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean show = on && i == page;
            r.label.setVisible(show);
            r.icon.setVisible(show);
            r.label.setCompositesDirty(true);
            r.icon.setCompositesDirty(true);
        }
        if (viewport != null) {
            viewport.setCompositesDirty(true);
        }
    }

    /** Swaps the visible row every dwell; the clipped viewport is ready for the animated slide later. */
    private void startPager() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (attached) {
                    try {
                        Thread.sleep(config.dwellMs());
                    } catch (InterruptedException e) {
                        return;
                    }
                    poster.post(new Runnable() {
                        public void run() {
                            if (rows.size() > 1) {
                                page = (page + 1) % rows.size();
                                showPage();
                            }
                        }
                    });
                }
            }
        }, "mibsdk-status-bar");
        t.setDaemon(true);
        t.start();
    }

    private static final class Item {
        final String text;
        final int stockIconId;
        final String filePath;
        final boolean ownColours;

        Item(String text, int stockIconId, String filePath, boolean ownColours) {
            this.text = text;
            this.stockIconId = stockIconId;
            this.filePath = filePath;
            this.ownColours = ownColours;
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
