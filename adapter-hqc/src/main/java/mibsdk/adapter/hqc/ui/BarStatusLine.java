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
import de.esolutions.hmi.widgets.audi.evo.high.widgets.LabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LayoutContainerController;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import mibsdk.platform.UiStatusPart;
import mibsdk.runtime.RingLog;
import mibsdk.runtime.StatusBarConfig;

/**
 * Renders the SDK status line in the built-in car status bar (right group), with the clipped viewport the
 * pagination needs. It attaches lazily on HMIEvent through the terminal's {@link WidgetRegistry}; before the bar
 * exists it throws, so the caller can fall back to popup 609600 / popup 65.
 *
 * <p>Skeleton: one text label with an RGBA colour. The item rows, custom file icons, page timer and the
 * user order come next (see docs/research/2026-10-28-status-bar-api.md).
 */
final class BarStatusLine implements StatusLineSink {

    private static final String CONFIG = "/mnt/persist/mibsdk/statusbar.properties";
    private static final int WIDTH = 200;
    private static final int HEIGHT = 24;

    private final Object frameworkAccess;
    private final HmiPoster poster;
    private final RingLog log;
    private final StatusBarConfig config;

    private volatile boolean attached;
    private volatile boolean visible = true;
    private volatile String pendingText;
    private LabelController label;
    private LayoutContainerController viewport;
    private LayoutContainerController group;

    BarStatusLine(Object frameworkAccess, HmiPoster poster, RingLog log) {
        this.frameworkAccess = frameworkAccess;
        this.poster = poster;
        this.log = log;
        this.config = StatusBarConfig.load(new File(CONFIG));
    }

    public boolean available() {
        return attached;
    }

    public void show(List<UiStatusPart> parts, String textLine) {
        if (!attached) {
            pendingText = textLine;
            poster.post(new Runnable() {
                public void run() {
                    attach();
                }
            });
            throw new IllegalStateException("the status bar is not attached yet");
        }
        final String text = textLine;
        poster.post(new Runnable() {
            public void run() {
                setText(text);
            }
        });
    }

    public void hide() {
        visible = false;
        poster.post(new Runnable() {
            public void run() {
                if (viewport != null) {
                    viewport.setVisible(false);
                    viewport.setCompositesDirty(true);
                }
            }
        });
    }

    /** HMIEvent: find the global bar, make the clipped viewport, add the label. */
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
            viewport.setBounds(Math.max(0, w - WIDTH), 6, WIDTH, HEIGHT);

            label = new LabelController();
            LabelRendererHigh lr = new LabelRendererHigh(label);
            label.setRenderer(lr);
            lr.setFonts(FontFactory.getFonts(0, 0, fw));
            label.setBounds(0, 0, WIDTH, HEIGHT);
            label.setColorIndices(new int[] {1, 2, 4, 0});
            viewport.add(label);
            group.add(viewport);
            viewport.setCompositesDirty(true);
            group.setCompositesDirty(true);
            attached = true;
            if (pendingText != null) {
                setText(pendingText);
            }
            log("attached: rightGroup " + w + "x" + group.getHeight());
        } catch (Throwable t) {
            log("attach failed: " + t);
        }
    }

    /** HMIEvent: one label for now; rows/icons/pagination come next. */
    private void setText(String text) {
        if (label == null) {
            return;
        }
        label.setText(text);
        label.setVisible(visible);
        label.setCompositesDirty(true);
        if (viewport != null) {
            viewport.setVisible(visible);
            viewport.setCompositesDirty(true);
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
