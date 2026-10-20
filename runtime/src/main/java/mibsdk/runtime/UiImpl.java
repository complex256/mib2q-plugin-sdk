package mibsdk.runtime;

import java.util.Set;
import mibsdk.api.Features;
import mibsdk.api.Listener;
import mibsdk.api.Page;
import mibsdk.api.PageSpec;
import mibsdk.api.PermissionDeniedException;
import mibsdk.api.Permissions;
import mibsdk.api.StatusItem;
import mibsdk.api.Subscription;
import mibsdk.api.Ui;
import mibsdk.api.UiAction;

/**
 * One plugin's {@link Ui}. Each call checks the feature first, at call time ({@link LiveFeatures}), then the
 * permission, then the per-plugin count limit; text is never rejected (the hub cleans and truncates it).
 * Every returned {@link Page}, Car row and {@link StatusItem} is tracked in the plugin's {@link Registrations}.
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
    private final Object lock = new Object();
    private int pages; // live pages; guarded by lock
    private int rows; // live Car rows; guarded by lock
    private int statusItems; // live status items; guarded by lock
    private int rowSeq; // guarded by lock
    private boolean closed; // guarded by lock; set when the plugin's registrations are cancelled

    UiImpl(String pluginId, UiHub hub, LiveFeatures features, Set<String> granted, SerialExecutor exec,
            Registrations registrations) {
        this.pluginId = pluginId;
        this.hub = hub;
        this.features = features;
        this.granted = granted;
        this.exec = exec;
        this.registrations = registrations;
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
        requireFeature(Features.UI_PAGES);
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        PageHandle h = new PageHandle(this, spec.id());
        synchronized (lock) {
            if (closed) {
                h.cancelled = true;
                return h;
            }
            reserve(PAGES);
            try {
                hub.addPage(pluginId, spec, onAction, exec);
            } catch (RuntimeException e) {
                release(PAGES);
                throw e;
            }
        }
        registrations.add(h); // closed (plugin stopping): cancelled at once
        return h;
    }

    @Override
    public Subscription carMenuRow(String label, Page opens) {
        requireFeature(Features.SLOT_CAR_MENU);
        requirePermission(Permissions.UI_SLOT_CAR_MENU);
        RowHandle h;
        synchronized (lock) {
            h = new RowHandle("row." + (++rowSeq));
            if (closed) { // its page may be one made after close (cancelled): not an error for a stopped plugin
                h.cancelled = true;
                return h;
            }
            String pageKey = ownPageKey(opens);
            if (pageKey == null) {
                throw new IllegalArgumentException("a Car row must open a live page from this plugin's ui().page()");
            }
            reserve(ROWS);
            try {
                hub.addCarRow(pluginId, h.rowKey, label, pageKey);
            } catch (RuntimeException e) {
                release(ROWS);
                throw e;
            }
        }
        registrations.add(h);
        return h;
    }

    @Override
    public StatusItem statusItem(String text) {
        requireFeature(Features.SLOT_STATUS_BAR);
        requirePermission(Permissions.UI_SLOT_STATUS_BAR);
        StatusHandle h = new StatusHandle();
        synchronized (lock) {
            if (closed) {
                h.cancelled = true;
                return h;
            }
            reserve(STATUS);
            try {
                hub.setStatusItem(pluginId, text);
            } catch (RuntimeException e) {
                release(STATUS);
                throw e;
            }
        }
        registrations.add(h);
        return h;
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

    private void requireFeature(String feature) {
        if (!features.has(feature)) {
            throw new IllegalStateException("feature " + feature + " not available");
        }
    }

    private void requirePermission(String permission) {
        if (!granted.contains(permission)) {
            throw new PermissionDeniedException(permission);
        }
    }

    /** The key of {@code p} if it is a live page made by this object; null otherwise. Calls nothing on {@code p}. */
    private String ownPageKey(Page p) {
        if (!(p instanceof PageHandle)) {
            return null;
        }
        PageHandle h = (PageHandle) p;
        return h.ui == this && !h.cancelled ? pluginId + "/" + h.pageId : null;
    }

    // ---- handles ----

    /** The plugin's handle on one page. The hub accepts only these as link targets. */
    static final class PageHandle implements Page {
        private final UiImpl ui;
        private final String pageId;
        private final Object lock = new Object();
        volatile boolean cancelled; // written under lock (or before publication); read without it by the hub

        PageHandle(UiImpl ui, String pageId) {
            this.ui = ui;
            this.pageId = pageId;
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
                if (!cancelled) {
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
            if (!cancelled) {
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
                    if (!ui.closed) { // closed: the runtime removes the owner's pages, maybe already re-registered
                        ui.hub.removePage(ui.pluginId, pageId);
                    }
                }
            }
            ui.release(PAGES);
            ui.registrations.remove(this);
        }
    }

    private final class RowHandle implements Subscription {
        final String rowKey;
        private final Object hlock = new Object();
        boolean cancelled; // guarded by hlock (set before publication for a handle made after close)

        RowHandle(String rowKey) {
            this.rowKey = rowKey;
        }

        @Override
        public void cancel() {
            synchronized (hlock) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                synchronized (lock) {
                    if (!closed) {
                        hub.removeCarRow(pluginId, rowKey);
                    }
                }
            }
            release(ROWS);
            registrations.remove(this);
        }
    }

    private final class StatusHandle implements StatusItem {
        private final Object hlock = new Object();
        boolean cancelled; // guarded by hlock (set before publication for a handle made after close)

        @Override
        public void set(String text) {
            synchronized (hlock) {
                if (!cancelled) {
                    synchronized (lock) {
                        if (!closed) {
                            hub.setStatusItem(pluginId, text);
                        }
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
                    if (!closed) {
                        hub.setStatusItem(pluginId, null);
                    }
                }
            }
            release(STATUS);
            registrations.remove(this);
        }
    }
}
