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
import mibsdk.api.Permissions;
import mibsdk.api.StatusItem;
import mibsdk.api.StockIcons;
import mibsdk.api.Subscription;
import mibsdk.api.Ui;
import mibsdk.api.UiAction;
import mibsdk.platform.IconIndex;
import mibsdk.platform.UiDialog;
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
 *
 * <p>Api 5 (inputs): {@link #page} and {@link PageHandle#update} check every item icon (this plugin's handle, at most
 * {@link PageSpec#ITEM_ICON_MAX_PX} installed), then hand the hub {@link #forUnit} of the spec: the feature decision
 * ({@link Features#UI_INPUTS}, {@link Features#UI_ICONS}) is made here, so the hub only ever sees entries the unit
 * can draw. {@link #confirm} and {@link #speller} go to the hub with {@link Features#UI_PAGES} and
 * {@link Features#UI_INPUTS}, {@link #toast} with {@link Features#SLOT_STATUS_BAR} and {@link Features#UI_INPUTS}.
 * Without them a confirm or speller is inert: it posts its one answer (confirm {@code value 0}, speller
 * {@code text null}) on the plugin's executor; an inert toast checks its permission and arguments and shows nothing.
 * Either way an answer is delivered at most once, never after its handle is cancelled.
 */
final class UiImpl implements Ui {

    static final int MAX_PAGES = 8;
    static final int MAX_CAR_ROWS = 2;
    static final int MAX_STATUS_ITEMS = 1;

    private final String pluginId;
    private final UiHub hub;
    private final LiveFeatures features;
    private final Set<String> granted;
    private final Set<String> declared;
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
     * @param granted the effective permissions; {@code declared} is what the manifest lists, to tell a denial apart
     * @param declaredIcons the manifest's {@code icons}
     * @param iconDir the plugin's installed icons ({@link RuntimeConfig#pluginIconDir}); null if it has none
     */
    UiImpl(String pluginId, UiHub hub, LiveFeatures features, Set<String> granted, Set<String> declared,
            SerialExecutor exec, Registrations registrations, List<String> declaredIcons, File iconDir) {
        this.pluginId = pluginId;
        this.hub = hub;
        this.features = features;
        this.granted = granted;
        this.declared = declared;
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
        checkItemIcons(spec);
        PageHandle h = new PageHandle(this, spec.id(), !features.has(Features.UI_PAGES));
        synchronized (lock) {
            if (closed) {
                h.cancelled = true;
                return h;
            }
            reserve(PAGES);
            if (!h.inert) {
                try {
                    hub.addPage(pluginId, fitted(spec), onAction, exec);
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
                h = new IconHandle(this, name, u == null ? null : u.withOwnColours(colour == Icon.OWN_COLOURS));
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
        return new IconHandle(this, name, UiIcon.stock(name));
    }

    // ---- api 5: confirm, toast, speller ----

    @Override
    public Subscription confirm(String title, String text, String yes, String no, Listener<UiAction> onAnswer) {
        if (!dialogs()) {
            return inertAnswer(onAnswer, new UiAction(null, null, null, new Integer(0), null), "ui:confirm");
        }
        return dialog(UiDialog.CONFIRM, title, text, yes, no, 0, onAnswer);
    }

    @Override
    public void toast(String text, Icon icon) {
        requirePermission(Permissions.UI_SLOT_STATUS_BAR);
        UiIcon shown = shown(ownIcon(icon, true));
        if (!features.has(Features.SLOT_STATUS_BAR) || !features.has(Features.UI_INPUTS)) {
            return;
        }
        synchronized (lock) {
            if (!closed) {
                hub.showToast(pluginId, text, shown);
            }
        }
    }

    @Override
    public Subscription speller(String title, String initial, int maxLen, Listener<UiAction> onDone) {
        if (maxLen < 1 || maxLen > MAX_SPELLER) {
            throw new IllegalArgumentException("maxLen must be 1.." + MAX_SPELLER + ", not " + maxLen);
        }
        if (!dialogs()) {
            return inertAnswer(onDone, new UiAction(null, null, null, null, null), "ui:speller");
        }
        return dialog(UiDialog.SPELLER, title, initial, null, null, maxLen, onDone);
    }

    /** The unit shows confirm and speller (fixed for a boot). */
    private boolean dialogs() {
        return features.has(Features.UI_PAGES) && features.has(Features.UI_INPUTS);
    }

    /**
     * A live confirm or speller: the hub answers it once (at once if it cannot open), through a listener that drops
     * the answer once the handle is cancelled; cancel also takes it off screen. Once closed (the plugin is stopping)
     * it comes back cancelled, unanswered.
     */
    private Subscription dialog(int kind, String title, String text, String yes, String no, int maxLen,
            final Listener<UiAction> l) {
        final AnswerHandle h = new AnswerHandle(this);
        Listener<UiAction> gated = l == null ? null : new Listener<UiAction>() {
            public void on(UiAction a) throws Exception {
                if (h.take()) {
                    l.on(a);
                }
            }
        };
        synchronized (lock) {
            if (closed) {
                h.cancel();
                return h;
            }
            h.seq = hub.requestDialog(pluginId, kind, title, text, yes, no, maxLen, gated, exec);
        }
        return h;
    }

    /**
     * An inert confirm or speller: {@code answer} is posted once on the plugin's executor, unless the returned
     * handle is cancelled before it runs. Once closed (the plugin is stopping) it comes back cancelled, unanswered.
     */
    private Subscription inertAnswer(final Listener<UiAction> l, final UiAction answer, String label) {
        final AnswerHandle h = new AnswerHandle(null);
        synchronized (lock) {
            if (closed) {
                h.cancel();
                return h;
            }
        }
        if (l != null) {
            exec.submit(label, new Runnable() {
                public void run() {
                    if (!h.take()) {
                        return;
                    }
                    try {
                        l.on(answer);
                    } catch (Exception e) {
                        EventBus.<RuntimeException>sneakyThrow(e);
                    }
                }
            });
        }
        return h;
    }

    // ---- api 5: fitting a page to the unit ----

    /** {@link #forUnit} with this boot's features (fixed per boot, so fitting at page/update time is enough). */
    private PageSpec fitted(PageSpec spec) {
        return forUnit(spec, features.has(Features.UI_INPUTS), features.has(Features.UI_ICONS));
    }

    /**
     * {@code spec} as this unit can draw it, rebuilt through the public builders only. Without {@code inputs} each
     * CHOICE, DROPDOWN, SLIDER and PROGRESS entry becomes an INFO line in the {@code UiPageText.line} style
     * ({@code "Level: Mid"}, {@code "Bass: 3"}, {@code "Progress: 60%"}); {@code enabled(false)} is re-applied only to
     * entries that stay list rows. Without {@code icons} item icons are dropped. ITEM, TOGGLE, INFO and LINK pass
     * through unchanged. Returns {@code spec} itself when nothing changes.
     */
    static PageSpec forUnit(PageSpec spec, boolean inputs, boolean icons) {
        List<PageSpec.Entry> in = spec.entries();
        boolean change = false;
        for (int i = 0; i < in.size() && !change; i++) {
            PageSpec.Entry e = (PageSpec.Entry) in.get(i);
            change = (!inputs && isInputKind(e.kind())) || (!icons && e.icon() != null);
        }
        if (!change) {
            return spec;
        }
        PageSpec out = PageSpec.of(spec.id(), spec.title());
        for (int i = 0; i < in.size(); i++) {
            PageSpec.Entry e = (PageSpec.Entry) in.get(i);
            PageSpec.Kind k = e.kind();
            String id = e.itemId();
            boolean listRow = true;
            if (k == PageSpec.Kind.ITEM) {
                out = out.item(id, e.label(), e.value(), icons ? e.icon() : null);
            } else if (k == PageSpec.Kind.TOGGLE) {
                out = out.toggle(id, e.label(), e.on());
            } else if (k == PageSpec.Kind.INFO) {
                out = out.info(e.label());
                listRow = false;
            } else if (k == PageSpec.Kind.LINK) {
                out = out.link(id, e.label(), e.target());
            } else if (inputs) {
                if (k == PageSpec.Kind.CHOICE) {
                    out = out.choice(id, e.label(), e.options(), e.number());
                } else if (k == PageSpec.Kind.DROPDOWN) {
                    out = out.dropdown(id, e.label(), e.options(), e.number());
                } else if (k == PageSpec.Kind.SLIDER) {
                    out = out.slider(id, e.label(), e.min(), e.max(), e.step(), e.number(), e.centred());
                } else {
                    out = out.progress(id, e.label(), e.number());
                }
                listRow = k == PageSpec.Kind.CHOICE;
            } else {
                out = out.info(textLine(e));
                listRow = false;
            }
            if (listRow && !e.enabled()) {
                out = out.enabled(id, false);
            }
        }
        return out;
    }

    private static boolean isInputKind(PageSpec.Kind k) {
        return k == PageSpec.Kind.CHOICE || k == PageSpec.Kind.DROPDOWN || k == PageSpec.Kind.SLIDER
                || k == PageSpec.Kind.PROGRESS;
    }

    /** "label: value" for an input entry shown as text: the option, the level, or the percent with "%". */
    private static String textLine(PageSpec.Entry e) {
        String v;
        if (e.kind() == PageSpec.Kind.CHOICE || e.kind() == PageSpec.Kind.DROPDOWN) {
            v = e.options()[e.number()];
        } else if (e.kind() == PageSpec.Kind.PROGRESS) {
            v = e.number() + "%";
        } else {
            v = String.valueOf(e.number());
        }
        String label = e.label();
        return label == null || label.length() == 0 ? v : label + ": " + v;
    }

    /**
     * Every item icon in {@code spec} must be this plugin's handle and, when installed, at most
     * {@link PageSpec#ITEM_ICON_MAX_PX} either way; else {@link IllegalArgumentException}. Independent of the features.
     */
    private void checkItemIcons(PageSpec spec) {
        List<PageSpec.Entry> in = spec.entries();
        for (int i = 0; i < in.size(); i++) {
            Icon icon = ((PageSpec.Entry) in.get(i)).icon();
            if (icon == null) {
                continue;
            }
            UiIcon u = ownIcon(icon, false);
            if (u != null && (u.width() > PageSpec.ITEM_ICON_MAX_PX || u.height() > PageSpec.ITEM_ICON_MAX_PX)) {
                throw new IllegalArgumentException("item icon '" + ((IconHandle) icon).name + "' is " + u.width()
                        + "x" + u.height() + "; page items take at most " + PageSpec.ITEM_ICON_MAX_PX + "x"
                        + PageSpec.ITEM_ICON_MAX_PX);
            }
        }
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
            throw UndeclaredPermissionException.denial(permission, declared);
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
            ui.checkItemIcons(spec);
            synchronized (lock) {
                if (!cancelled && !inert) {
                    synchronized (ui.lock) {
                        if (!ui.closed) {
                            ui.hub.updatePage(ui.pluginId, ui.fitted(spec));
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
        /** The declared or stock name. */
        final String name;
        /** Null when inert (declared but not installed). */
        final UiIcon icon;

        IconHandle(UiImpl ui, String name, UiIcon icon) {
            this.ui = ui;
            this.name = name;
            this.icon = icon;
        }
    }

    /** A confirm or speller: answered at most once, never after {@link #cancel()}. */
    static final class AnswerHandle implements Subscription {
        /** The UiImpl of a live dialog, so cancel takes it off screen; null for an inert one. */
        private final UiImpl ui;
        private final Object hlock = new Object();
        private boolean done; // guarded by hlock
        /** The hub's seq of a live dialog that opened; 0 otherwise. Written under ui.lock. */
        long seq;

        AnswerHandle(UiImpl ui) {
            this.ui = ui;
        }

        /** True once: the answer may be delivered now. False after cancel() or an earlier take(). */
        boolean take() {
            synchronized (hlock) {
                if (done) {
                    return false;
                }
                done = true;
                return true;
            }
        }

        @Override
        public void cancel() {
            synchronized (hlock) {
                done = true;
            }
            if (ui != null) {
                synchronized (ui.lock) {
                    if (!ui.closed && seq != 0) {
                        ui.hub.cancelDialog(ui.pluginId, seq);
                    }
                }
            }
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
