package mibsdk.runtime;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mibsdk.api.Features;
import mibsdk.api.Icon;
import mibsdk.api.Listener;
import mibsdk.api.Page;
import mibsdk.api.PageSpec;
import mibsdk.api.PermissionDeniedException;
import mibsdk.api.Permissions;
import mibsdk.api.StatusItem;
import mibsdk.api.StockIcons;
import mibsdk.api.Subscription;
import mibsdk.api.Ui;
import mibsdk.api.UiAction;
import mibsdk.platform.IconIndex;
import mibsdk.platform.UiIcon;

/**
 * One plugin's {@link Ui}. Each call checks its arguments and the permission, then the per-plugin count limit,
 * then the feature, at call time ({@link LiveFeatures}); text is never rejected (the hub cleans and truncates it).
 * Every returned {@link Page}, Car row and {@link StatusItem} is tracked in the plugin's {@link Registrations}.
 *
 * <p>Inert handles: when the feature is missing (the SDK UI is off this boot: wrap-loop latch, {@code ui.enabled=false},
 * a count or init failure; or the unit has no such slot) the call still succeeds and returns an inert handle. It
 * holds its count slot and validates its arguments like a live one, but never reaches the hub, so a plugin's other
 * work (a parking mute, a poll) keeps running with the UI off. A handle made inert stays inert if the feature appears
 * later; a Car row for an inert page is inert too.
 *
 * <p>Link targets and a Car row's page are resolved here, on the plugin's own thread: only a live
 * {@link PageHandle} made by this object is accepted; a foreign, cancelled or plugin-made {@link Page} is
 * rejected with {@link IllegalArgumentException} without calling any of its methods.
 *
 * <p>Closed: the first subscription in the plugin's {@link Registrations} closes this object, so from the moment the
 * runtime cancels the plugin's registrations no call through it or its handles reaches the hub. The hub keys
 * contributions by plugin id; without this a callback of the old run still hung after a reload could add to, or
 * cancel a page of, the new run. New pages, rows and status items asked for once closed come back cancelled.
 *
 * <p>Locking: handles and this object lock private monitors only, never themselves, so plugin code
 * synchronizing on them cannot block the runtime. Every hub call is made holding {@link #lock}, after checking
 * {@link #closed}; a handle takes its own lock first, then {@link #lock} (never the other way round). The hub reads
 * handles through volatile fields only.
 *
 * <p>Icons: only an {@link IconHandle} made by this object is accepted, like a {@link PageHandle}. {@link #icon} takes
 * a name the manifest declares and reads the plugin's installed {@link IconIndex} once, on first use; a declared name
 * the index lacks gives an inert handle (no {@link UiIcon}) and one WARN, in either colour. One handle is cached per
 * (name, colour); the colours share the index entry and differ only in {@link UiIcon#ownColours}. Every check (declared name, own handle, the
 * {@link StatusItem#ICON_MAX_PX} limit) runs whether or not the unit has {@link Features#UI_ICONS}; without it a row or
 * item simply holds no icon.
 */
final class UiImpl implements Ui {

    static final int MAX_PAGES = 8;
    static final int MAX_CAR_ROWS = 2;
    static final int MAX_STATUS_ITEMS = 1;

    private final String pluginId;
    private final UiHub hub;
    private final LiveFeatures features;
    private final Set<String> granted;
    private final SerialExecutor exec;
    private final Registrations registrations;
    private final List<String> declaredIcons;
    private final File iconDir;
    private final Object lock = new Object();
    private final Object iconLock = new Object(); // never held with lock or a handle's lock
    private IconIndex iconIndex; // guarded by iconLock; read on the first icon(name)
    private final Map<String, IconHandle> icons = new HashMap<String, IconHandle>(); // by name[+own]; iconLock
    private int pages; // live pages; guarded by lock
    private int rows; // live Car rows; guarded by lock
    private int statusItems; // live status items; guarded by lock
    private int rowSeq; // guarded by lock
    private boolean closed; // guarded by lock; set when the plugin's registrations are cancelled

    /**
     * @param declaredIcons the manifest's {@code icons}
     * @param iconDir the plugin's installed icons ({@link RuntimeConfig#pluginIconDir}); null if it has none
     */
    UiImpl(String pluginId, UiHub hub, LiveFeatures features, Set<String> granted, SerialExecutor exec,
            Registrations registrations, List<String> declaredIcons, File iconDir) {
        this.pluginId = pluginId;
        this.hub = hub;
        this.features = features;
        this.granted = granted;
        this.exec = exec;
        this.registrations = registrations;
        this.declaredIcons = declaredIcons;
        this.iconDir = iconDir;
        registrations.add(new Subscription() { // already closed (plugin stopping): cancelled, so closed, at once
            public void cancel() {
                synchronized (lock) {
                    closed = true;
                }
            }
        });
    }

    @Override
    public Page page(PageSpec spec, Listener<UiAction> onAction) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        PageHandle h = new PageHandle(this, spec.id(), !features.has(Features.UI_PAGES));
        synchronized (lock) {
            if (closed) {
                h.cancelled = true;
                return h;
            }
            reserve(PAGES);
            if (!h.inert) {
                try {
                    hub.addPage(pluginId, spec, onAction, exec);
                } catch (RuntimeException e) {
                    release(PAGES);
                    throw e;
                }
            }
        }
        registrations.add(h); // closed (plugin stopping): cancelled at once
        return h;
    }

    @Override
    public Subscription carMenuRow(String label, Page opens) {
        return carMenuRow(label, opens, null);
    }

    @Override
    public Subscription carMenuRow(String label, Page opens, Icon icon) {
        requirePermission(Permissions.UI_SLOT_CAR_MENU);
        UiIcon shown = shown(ownIcon(icon, false));
        RowHandle h;
        synchronized (lock) {
            if (closed) { // its page may be one made after close (cancelled): not an error for a stopped plugin
                h = new RowHandle("row." + (++rowSeq), false, shown);
                h.cancelled = true;
                return h;
            }
            PageHandle page = ownLivePage(opens);
            if (page == null) {
                throw new IllegalArgumentException("a Car row must open a live page from this plugin's ui().page()");
            }
            h = new RowHandle("row." + (++rowSeq), page.inert || !features.has(Features.SLOT_CAR_MENU), shown);
            reserve(ROWS);
            if (!h.inert) {
                try {
                    hub.addCarRow(pluginId, h.rowKey, label, pluginId + "/" + page.pageId, h.icon);
                } catch (RuntimeException e) {
                    release(ROWS);
                    throw e;
                }
            }
        }
        registrations.add(h);
        return h;
    }

    @Override
    public StatusItem statusItem(String text) {
        return statusItem(text, null);
    }

    @Override
    public StatusItem statusItem(String text, Icon icon) {
        requirePermission(Permissions.UI_SLOT_STATUS_BAR);
        StatusHandle h = new StatusHandle(!features.has(Features.SLOT_STATUS_BAR), shown(ownIcon(icon, true)));
        synchronized (lock) {
            if (closed) {
                h.cancelled = true;
                return h;
            }
            reserve(STATUS);
            if (!h.inert) {
                try {
                    hub.setStatusItem(pluginId, text, h.icon);
                } catch (RuntimeException e) {
                    release(STATUS);
                    throw e;
                }
            }
        }
        registrations.add(h);
        return h;
    }

    @Override
    public Icon icon(String name) {
        return icon(name, Icon.TINTED);
    }

    @Override
    public Icon icon(String name, int colour) {
        if (colour != Icon.TINTED && colour != Icon.OWN_COLOURS) {
            throw new IllegalArgumentException("colour must be Icon.TINTED or Icon.OWN_COLOURS");
        }
        if (name == null || !declaredIcons.contains(name)) {
            throw new IllegalArgumentException("icon '" + name + "' is not declared in plugin " + pluginId
                    + "'s plugin.json \"icons\"");
        }
        synchronized (iconLock) {
            String cacheKey = colour == Icon.OWN_COLOURS ? name + "+own" : name; // '+' is never in a name
            IconHandle h = (IconHandle) icons.get(cacheKey);
            if (h == null) {
                if (iconIndex == null) {
                    iconIndex = IconIndex.read(pluginId, iconDir); // never throws
                }
                UiIcon u = iconIndex.get(name);
                if (u == null && !icons.containsKey(colour == Icon.OWN_COLOURS ? name : name + "+own")) {
                    hub.warn(pluginId, "icon '" + name + "' is declared but not installed (jar pushed without its"
                            + " package?): shown without it");
                }
                h = new IconHandle(this, u == null ? null : u.withOwnColours(colour == Icon.OWN_COLOURS));
                icons.put(cacheKey, h);
            }
            return h;
        }
    }

    @Override
    public Icon stockIcon(String name) {
        if (!StockIcons.isKnown(name)) {
            throw new IllegalArgumentException("unknown stock icon '" + name + "'");
        }
        return new IconHandle(this, UiIcon.stock(name));
    }

    // ---- limits ----

    private static final int PAGES = 0;
    private static final int ROWS = 1;
    private static final int STATUS = 2;

    private void reserve(int what) {
        synchronized (lock) {
            if (what == PAGES) {
                if (pages >= MAX_PAGES) {
                    throw new IllegalStateException("plugin " + pluginId + " already has " + MAX_PAGES + " pages");
                }
                pages++;
            } else if (what == ROWS) {
                if (rows >= MAX_CAR_ROWS) {
                    throw new IllegalStateException("plugin " + pluginId + " already has " + MAX_CAR_ROWS
                            + " Car rows");
                }
                rows++;
            } else {
                if (statusItems >= MAX_STATUS_ITEMS) {
                    throw new IllegalStateException("plugin " + pluginId + " already has a status item");
                }
                statusItems++;
            }
        }
    }

    private void release(int what) {
        synchronized (lock) {
            if (what == PAGES) {
                pages--;
            } else if (what == ROWS) {
                rows--;
            } else {
                statusItems--;
            }
        }
    }

    // ---- checks ----

    private void requirePermission(String permission) {
        if (!granted.contains(permission)) {
            throw new PermissionDeniedException(permission);
        }
    }

    /**
     * The {@link UiIcon} of {@code i}, an icon made by this object (null for none or an inert one). Throws
     * {@link IllegalArgumentException} for any other {@link Icon}, or, for a status item, for an installed icon over
     * {@link StatusItem#ICON_MAX_PX}. Calls nothing on {@code i}. Independent of {@link Features#UI_ICONS}.
     */
    private UiIcon ownIcon(Icon i, boolean status) {
        if (i == null) {
            return null;
        }
        if (!(i instanceof IconHandle) || ((IconHandle) i).ui != this) {
            throw new IllegalArgumentException("an icon must come from this plugin's ui().icon() or ui().stockIcon()");
        }
        UiIcon u = ((IconHandle) i).icon;
        if (status && u != null && (u.width() > StatusItem.ICON_MAX_PX || u.height() > StatusItem.ICON_MAX_PX)) {
            throw new IllegalArgumentException("a status icon is at most " + StatusItem.ICON_MAX_PX + "x"
                    + StatusItem.ICON_MAX_PX + " px, this one is " + u.width() + "x" + u.height());
        }
        return u;
    }

    /** {@code u} if the unit draws icons now ({@link Features#UI_ICONS}); null otherwise. */
    private UiIcon shown(UiIcon u) {
        return u != null && features.has(Features.UI_ICONS) ? u : null;
    }

    /** {@code p} if it is a live (maybe inert) page made by this object; null otherwise. Calls nothing on {@code p}. */
    private PageHandle ownLivePage(Page p) {
        if (!(p instanceof PageHandle)) {
            return null;
        }
        PageHandle h = (PageHandle) p;
        return h.ui == this && !h.cancelled ? h : null;
    }

    // ---- handles ----

    /** The plugin's handle on one page. The hub accepts only these as link targets. */
    static final class PageHandle implements Page {
        private final UiImpl ui;
        private final String pageId;
        /** Made without Features.UI_PAGES: never reaches the hub. */
        final boolean inert;
        private final Object lock = new Object();
        volatile boolean cancelled; // written under lock (or before publication); read without it by the hub

        PageHandle(UiImpl ui, String pageId, boolean inert) {
            this.ui = ui;
            this.pageId = pageId;
            this.inert = inert;
        }

        String owner() {
            return ui.pluginId;
        }

        String pageId() {
            return pageId;
        }

        boolean isCancelled() {
            return cancelled;
        }

        @Override
        public String id() {
            return pageId;
        }

        /** Throws {@link IllegalArgumentException} if {@code spec.id()} is not this page's id. */
        @Override
        public void update(PageSpec spec) {
            if (spec == null || !pageId.equals(spec.id())) {
                throw new IllegalArgumentException("update must keep page id '" + pageId + "'");
            }
            synchronized (lock) {
                if (!cancelled && !inert) {
                    synchronized (ui.lock) {
                        if (!ui.closed) {
                            ui.hub.updatePage(ui.pluginId, spec);
                        }
                    }
                }
            }
        }

        @Override
        public void open() {
            if (!cancelled && !inert) {
                synchronized (ui.lock) {
                    if (!ui.closed) {
                        ui.hub.requestOpen(ui.pluginId + "/" + pageId);
                    }
                }
            }
        }

        @Override
        public void cancel() {
            synchronized (lock) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                synchronized (ui.lock) {
                    if (!ui.closed && !inert) { // closed: the runtime removes the owner's pages, maybe re-registered
                        ui.hub.removePage(ui.pluginId, pageId);
                    }
                }
            }
            ui.release(PAGES);
            ui.registrations.remove(this);
        }
    }

    /** A plugin's icon: made only by {@link #icon} and {@link #stockIcon}, accepted only by the same object. */
    static final class IconHandle implements Icon {
        private final UiImpl ui;
        /** Null when inert (declared but not installed). */
        final UiIcon icon;

        IconHandle(UiImpl ui, UiIcon icon) {
            this.ui = ui;
            this.icon = icon;
        }
    }

    final class RowHandle implements Subscription {
        final String rowKey;
        /** Made without Features.SLOT_CAR_MENU, or for an inert page: never reaches the hub. */
        final boolean inert;
        /** What the row shows: null for none, an inert icon, or without Features.UI_ICONS. */
        final UiIcon icon;
        private final Object hlock = new Object();
        boolean cancelled; // guarded by hlock (set before publication for a handle made after close)

        RowHandle(String rowKey, boolean inert, UiIcon icon) {
            this.rowKey = rowKey;
            this.inert = inert;
            this.icon = icon;
        }

        @Override
        public void cancel() {
            synchronized (hlock) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                synchronized (lock) {
                    if (!closed && !inert) {
                        hub.removeCarRow(pluginId, rowKey);
                    }
                }
            }
            release(ROWS);
            registrations.remove(this);
        }
    }

    final class StatusHandle implements StatusItem {
        /** Made without Features.SLOT_STATUS_BAR: never reaches the hub. */
        final boolean inert;
        private final Object hlock = new Object();
        boolean cancelled; // guarded by hlock (set before publication for a handle made after close)
        /** What the item shows: null for none, an inert icon, or without Features.UI_ICONS. Written under hlock. */
        volatile UiIcon icon;

        StatusHandle(boolean inert, UiIcon icon) {
            this.inert = inert;
            this.icon = icon;
        }

        @Override
        public void set(String text, Icon icon) {
            UiIcon shown = shown(ownIcon(icon, true)); // checked even when cancelled or inert, like any argument
            synchronized (hlock) {
                this.icon = shown;
                publish(text);
            }
        }

        /** Keeps the item's icon. */
        @Override
        public void set(String text) {
            synchronized (hlock) {
                publish(text);
            }
        }

        /** hlock held. Text and icon reach the hub in one call, so they change together. */
        private void publish(String text) {
            if (!cancelled && !inert) {
                synchronized (lock) {
                    if (!closed) {
                        hub.setStatusItem(pluginId, text, icon);
                    }
                }
            }
        }

        @Override
        public void cancel() {
            synchronized (hlock) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                synchronized (lock) {
                    if (!closed && !inert) {
                        hub.setStatusItem(pluginId, null);
                    }
                }
            }
            release(STATUS);
            registrations.remove(this);
        }
    }
}
