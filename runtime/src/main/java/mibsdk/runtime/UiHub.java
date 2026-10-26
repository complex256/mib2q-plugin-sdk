package mibsdk.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mibsdk.api.Icon;
import mibsdk.api.Listener;
import mibsdk.api.Page;
import mibsdk.api.PageSpec;
import mibsdk.api.Scheduler;
import mibsdk.api.StatusItem;
import mibsdk.api.UiAction;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiRow;
import mibsdk.platform.UiSnapshot;
import mibsdk.platform.UiTable;
import mibsdk.platform.UiText;

/**
 * Folds every plugin's UI contributions (pages, Car rows, status items) and the SDK's own pages into one
 * immutable {@link UiSnapshot}, published to the {@link UiTable}; routes user input back to the owner.
 *
 * <p>Keys: a plugin page is {@code <owner>/<pageId>}, a plugin row {@code <owner>/<rowKey>}. Owner
 * {@code mibsdk} is reserved for the SDK's pages. Text is cleaned and truncated here ({@link UiText}),
 * once, when a contribution arrives; a snapshot is only assembled from already-clean parts.
 *
 * <p>Order: owners keep the order of {@link #registerOwner} (the runtime calls it as it enables a plugin), so
 * plugin rows follow enable order whatever the timing of the contributions; an owner never registered (tests)
 * takes the position of its first contribution. Car rows: the Plugins row first. Status items do not follow
 * enable order (a reload moves a plugin to the end): they follow the user's saved order, then plugin id
 * ({@link #setStatusPrefs}), and share the line fairly ({@link StatusLine}).
 * After {@link #removeOwner} an owner's contributions are ignored until it is registered again, so a
 * callback still running after its plugin stopped cannot bring its UI back or wipe the stopped notice.
 *
 * <p>Publishing is coalesced: every change asks for one publish on {@code publishTimer}, at the earliest
 * {@code publishMinMs} (on the {@link Clock}) after the previous one; changes made while a publish is
 * pending ride along with it, so the last change is always published. Versions strictly increase.
 *
 * <p>Locking: all state is behind one private lock. No plugin code ever runs while it is held, and no
 * plugin-supplied object is called: a link target is accepted only if it is one of the SDK's own
 * {@link UiImpl.PageHandle}s, read through its fields. Input reaches a plugin only as a task on its own
 * executor; input for a page with no live owner (disabled, quarantined, stopped) is dropped and logged
 * once per page.
 *
 * <p>Open pages: a page counts as open from {@link #requestOpen} or {@code PAGE_SHOWN} until
 * {@code PAGE_CLOSED}. When its owner is removed with a notice, an open page is replaced by a notice
 * page under the same key (one INFO entry, no owner) until it is closed or the owner registers it again.
 *
 * <p>Input entries (api 5): each CHOICE entry gets a derived choice page {@link #choiceKey} ({@code <pageKey>#<itemId>},
 * one RADIO per option); a pick there arrives as the CHOICE's index. Page events of a choice page count as its
 * parent's, so opening and leaving it never closes the plugin's page. DROPDOWN indexes out of range are dropped;
 * SLIDER values are clamped, snapped to the step and coalesced (one waiting action per slider). Any input on a
 * disabled entry is dropped and logged once per page key, whatever the unit's features.
 */
final class UiHub {

    static final String SDK_OWNER = "mibsdk";
    static final String PLUGINS_KEY = "mibsdk/plugins";
    static final String DEFAULT_PLUGINS_LABEL = "Plugins";
    static final int DEFAULT_STATUS_MAX_CHARS = StatusLine.MAX_LINE_CHARS;
    private static final String SOURCE = "mibsdk";
    /** Bounds the per-page bookkeeping fed by adapter input (page keys on screen, drop-logged keys). */
    private static final int MAX_TRACKED_KEYS = 32;

    private static final class PageRec {
        final String pageId;
        final UiPage page;
        final List<UiPage> choicePages; // one derived page per CHOICE entry, built with the page
        final Listener<UiAction> listener; // may be null
        final SerialExecutor exec;

        PageRec(String pageId, UiPage page, Listener<UiAction> listener, SerialExecutor exec) {
            this.pageId = pageId;
            this.page = page;
            this.choicePages = choicePages(page);
            this.listener = listener;
            this.exec = exec;
        }
    }

    /** The one SLIDER action waiting on a plugin's executor for one (page key, item id); newer values replace it. */
    private static final class PendingSlider {
        int value; // guarded by the hub's lock
    }

    private static final class Owner {
        final Map<String, PageRec> pages = new LinkedHashMap<String, PageRec>(); // by pageId, registration order
        final Map<String, UiRow> rows = new LinkedHashMap<String, UiRow>(); // by row key (full), registration order
        String status; // cleaned; null when hidden
        UiIcon statusIcon; // null for none, and while status is null
    }

    private final UiTable table;
    private final RingLog log;
    private final Clock clock;
    private final Scheduler publishTimer;
    private final long publishMinMs;

    private final Object lock = new Object();
    // ---- guarded by lock ----
    private final Map<String, Owner> owners = new LinkedHashMap<String, Owner>();
    private final Map<String, UiPage> notices = new HashMap<String, UiPage>();
    private final Map<String, String> openTitles = new LinkedHashMap<String, String>(); // open key -> last title
    private final Set<String> dropLogged = new HashSet<String>();
    /** Owners removed and not registered again: their stray contributions are ignored. */
    private final Set<String> removedOwners = new HashSet<String>();
    private List<UiPage> sdkPages = Collections.<UiPage>emptyList();
    private List<String> indicators = Collections.<String>emptyList();
    private String pluginsRowLabel = DEFAULT_PLUGINS_LABEL;
    private int statusMaxChars = DEFAULT_STATUS_MAX_CHARS;
    private List<String> statusOrder = Collections.<String>emptyList(); // the user's saved order (StatusPrefs)
    private Set<String> statusHidden = Collections.<String>emptySet(); // owners whose item the user hid
    private Listener<UiInput> sdkHandler;
    private long openSeq;
    private String openPageKey;
    private String shownPageKey; // the parent key of the last PAGE_SHOWN, until its PAGE_CLOSED
    /** Test seam: runs on the dispatcher between resolving a slider's page and queueing its action. */
    volatile Runnable sliderResolvedHook;
    /** By {@code <pageKey>#<itemId>}: the slider action waiting on its plugin's executor. */
    private final Map<String, PendingSlider> pendingSliders = new HashMap<String, PendingSlider>();
    private boolean publishPending;
    private boolean published;
    private long lastPublishAt;
    private boolean down; // guarded by lock; set once by publishDown
    // ---- publishing ----
    private final Object publishLock = new Object();
    private long version; // guarded by publishLock

    private final Runnable publishTask = new Runnable() {
        public void run() {
            publishNow();
        }
    };

    UiHub(UiTable table, RingLog log, Clock clock, Scheduler publishTimer, long publishMinMs) {
        this.table = table;
        this.log = log;
        this.clock = clock;
        this.publishTimer = publishTimer;
        this.publishMinMs = Math.max(0, publishMinMs);
    }

    /** A WARN on behalf of {@code owner}'s UI calls ({@link UiImpl} has no log of its own). */
    void warn(String owner, String msg) {
        log.append("WARN", owner, msg);
    }

    // ---- plugin contributions (called on the plugin's thread through UiImpl) ----

    /**
     * Registers page {@code spec.id()} for {@code owner}. Link targets must be live pages of the same owner
     * (SDK-made handles); otherwise, or for a duplicate page id, {@link IllegalArgumentException}.
     */
    void addPage(String owner, PageSpec spec, Listener<UiAction> l, SerialExecutor exec) {
        checkOwner(owner);
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        synchronized (lock) {
            if (removedOwners.contains(owner)) {
                return; // a callback still running after its plugin stopped: never resurrect the owner
            }
            Owner o = owners.get(owner);
            if (o != null && o.pages.containsKey(spec.id())) {
                throw new IllegalArgumentException("page '" + spec.id() + "' is already registered");
            }
            UiPage page = toUiPage(owner, o, spec);
            if (o == null) {
                o = new Owner();
                owners.put(owner, o);
            }
            o.pages.put(spec.id(), new PageRec(spec.id(), page, l, exec));
            pageChanged(page);
        }
        changed();
    }

    /**
     * Replaces the content of page {@code spec.id()}; the owner must have that page registered, else
     * {@link IllegalArgumentException} (so an update can only ever name the page it updates).
     */
    void updatePage(String owner, PageSpec spec) {
        checkOwner(owner);
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        synchronized (lock) {
            if (removedOwners.contains(owner)) {
                return;
            }
            Owner o = owners.get(owner);
            PageRec old = o == null ? null : (PageRec) o.pages.get(spec.id());
            if (old == null) {
                throw new IllegalArgumentException("no page '" + spec.id() + "' registered by " + owner);
            }
            UiPage page = toUiPage(owner, o, spec);
            o.pages.put(spec.id(), new PageRec(old.pageId, page, old.listener, old.exec));
            pageChanged(page);
        }
        changed();
    }

    void removePage(String owner, String pageId) {
        synchronized (lock) {
            Owner o = owners.get(owner);
            if (o == null || o.pages.remove(pageId) == null) {
                return;
            }
        }
        changed();
    }

    /** {@link #addCarRow(String, String, String, String, UiIcon)} without an icon. */
    void addCarRow(String owner, String rowKey, String label, String pageKey) {
        addCarRow(owner, rowKey, label, pageKey, null);
    }

    /**
     * {@code pageKey} must be a registered page of {@code owner}, else {@link IllegalArgumentException}.
     * {@code icon} is null for none.
     */
    void addCarRow(String owner, String rowKey, String label, String pageKey, UiIcon icon) {
        checkOwner(owner);
        String key = owner + "/" + rowKey;
        synchronized (lock) {
            if (removedOwners.contains(owner)) {
                return;
            }
            Owner o = owners.get(owner);
            if (o == null || pageKey == null || !pageKey.startsWith(owner + "/")
                    || !o.pages.containsKey(pageKey.substring(owner.length() + 1))) {
                throw new IllegalArgumentException("a Car row must open a registered page of " + owner);
            }
            if (o.rows.containsKey(key)) {
                throw new IllegalArgumentException("row '" + rowKey + "' is already registered");
            }
            o.rows.put(key, new UiRow(key, UiText.clean(label, PageSpec.MAX_LABEL), pageKey, icon));
        }
        changed();
    }

    void removeCarRow(String owner, String rowKey) {
        synchronized (lock) {
            Owner o = owners.get(owner);
            if (o == null || o.rows.remove(owner + "/" + rowKey) == null) {
                return;
            }
        }
        changed();
    }

    /** {@link #setStatusItem(String, String, UiIcon)} without an icon. */
    void setStatusItem(String owner, String text) {
        setStatusItem(owner, text, null);
    }

    /**
     * Text and icon change together, so no snapshot pairs one's new value with the other's old one. {@code null} or
     * text that cleans to empty removes the owner's item, icon included; {@code icon} is null for none.
     */
    void setStatusItem(String owner, String text, UiIcon icon) {
        checkOwner(owner);
        String clean = UiText.clean(text, StatusItem.MAX_TEXT);
        String value = clean.length() == 0 ? null : clean;
        UiIcon shown = value == null ? null : icon;
        synchronized (lock) {
            if (removedOwners.contains(owner)) {
                return;
            }
            Owner o = owners.get(owner);
            if (o == null) {
                if (value == null) {
                    return;
                }
                o = new Owner();
                owners.put(owner, o);
            }
            if ((value == null ? o.status == null : value.equals(o.status))
                    && (shown == null ? o.statusIcon == null : shown.equals(o.statusIcon))) {
                return;
            }
            o.status = value;
            o.statusIcon = shown;
        }
        changed();
    }

    // ---- runtime ----

    /** The plugin is being enabled: its rows and status item take this position (enable order). */
    void registerOwner(String owner) {
        checkOwner(owner);
        synchronized (lock) {
            removedOwners.remove(owner);
            if (!owners.containsKey(owner)) {
                owners.put(owner, new Owner());
            }
        }
    }

    /**
     * Drops everything {@code owner} contributed. With a notice, each of its pages that is open becomes a
     * notice page (one INFO entry); with {@code null} (a reload) open pages simply vanish.
     */
    void removeOwner(String owner, String stoppedNoticeOrNull) { // until registerOwner, its contributions are ignored
        boolean changed;
        synchronized (lock) {
            changed = owners.remove(owner) != null;
            if (owner != null) {
                removedOwners.add(owner);
                for (Iterator it = pendingSliders.keySet().iterator(); it.hasNext();) {
                    if (((String) it.next()).startsWith(owner + "/")) {
                        it.remove(); // the queued task finds no holder and sends nothing
                    }
                }
            }
            if (stoppedNoticeOrNull != null && owner != null) {
                String prefix = owner + "/";
                String text = UiText.clean(stoppedNoticeOrNull, PageSpec.MAX_INFO);
                List<UiEntry> entries = Collections.singletonList(new UiEntry(UiEntry.INFO, null, text, null, false, null));
                for (Iterator it = openTitles.entrySet().iterator(); it.hasNext();) {
                    Map.Entry e = (Map.Entry) it.next();
                    String key = (String) e.getKey();
                    if (key.startsWith(prefix)) {
                        String title = (String) e.getValue();
                        notices.put(key, new UiPage(key, title == null ? "" : title, entries));
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            changed();
        }
    }

    /** Page keys of {@code owner}, in registration order. */
    List<String> pageKeysOf(String owner) {
        synchronized (lock) {
            Owner o = owners.get(owner);
            List<String> out = new ArrayList<String>();
            if (o != null) {
                for (Iterator it = o.pages.keySet().iterator(); it.hasNext();) {
                    out.add(owner + "/" + it.next());
                }
            }
            return out;
        }
    }

    /** The title of the plugin page registered under {@code pageKey}, or null. */
    String pageTitle(String pageKey) {
        if (pageKey == null) {
            return null;
        }
        synchronized (lock) {
            PageRec rec = livePage(pageKey);
            return rec == null ? null : rec.page.title();
        }
    }

    /** The SDK's own pages (owner {@code mibsdk}); input on them goes to the SDK handler. */
    void setSdkPages(List<UiPage> pages) {
        List<UiPage> copy = pages == null ? Collections.<UiPage>emptyList()
                : Collections.unmodifiableList(new ArrayList<UiPage>(pages));
        synchronized (lock) {
            sdkPages = copy;
            for (int i = 0; i < copy.size(); i++) {
                pageChanged((UiPage) copy.get(i));
            }
        }
        changed();
    }

    /** Runtime-owned status-line items; they come before plugin items. */
    void setIndicators(List<String> list) {
        List<String> copy = list == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(list));
        synchronized (lock) {
            indicators = copy;
        }
        changed();
    }

    /**
     * The user's status-line choices ({@link StatusPrefs}): items follow {@code order}, then plugin-id order
     * ({@link StatusLine#order}); an owner in {@code hidden} keeps its item, but it is not composed. Null is empty.
     */
    void setStatusPrefs(List<String> order, Set<String> hidden) {
        List<String> o = order == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(order));
        Set<String> h = hidden == null ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new HashSet<String>(hidden));
        synchronized (lock) {
            statusOrder = o;
            statusHidden = h;
        }
        changed();
    }

    /** Owners that currently have a status item, hidden or not. */
    Set<String> statusOwners() {
        Set<String> out = new HashSet<String>();
        synchronized (lock) {
            for (Iterator it = owners.entrySet().iterator(); it.hasNext();) {
                Map.Entry e = (Map.Entry) it.next();
                if (((Owner) e.getValue()).status != null) {
                    out.add((String) e.getKey());
                }
            }
        }
        return out;
    }

    void setPluginsRowLabel(String label) {
        String clean = UiText.clean(label, PageSpec.MAX_LABEL);
        synchronized (lock) {
            pluginsRowLabel = clean;
        }
        changed();
    }

    void setStatusMaxChars(int max) {
        synchronized (lock) {
            statusMaxChars = Math.max(StatusLine.MIN_LINE_CHARS, max); // SAFE PDC R OFF is never cut
        }
        changed();
    }

    /** Asks the adapter to show {@code pageKey}: the next snapshot carries a higher openSeq. */
    void requestOpen(String pageKey) {
        if (pageKey == null) {
            return;
        }
        synchronized (lock) {
            openSeq++;
            openPageKey = pageKey;
            markOpen(pageKey);
        }
        changed();
    }

    /** The derived choice page of CHOICE entry {@code itemId} on page {@code pageKey}. */
    static String choiceKey(String pageKey, String itemId) {
        return pageKey + "#" + itemId;
    }

    /**
     * The page key of the last {@code PAGE_SHOWN} (a choice page counts as its parent), or null once that page's
     * {@code PAGE_CLOSED} arrived.
     */
    String shownPageKey() {
        synchronized (lock) {
            return shownPageKey;
        }
    }

    /** Receives input on SDK pages (owner {@code mibsdk}); runs on the dispatcher thread, so it must not block. */
    void setSdkHandler(Listener<UiInput> h) {
        synchronized (lock) {
            sdkHandler = h;
        }
    }

    /** Routes one user input. Called by the {@link UiDispatcher} thread; never throws. */
    void dispatch(UiInput in) {
        try {
            dispatchUnguarded(in);
        } catch (Throwable t) {
            log.append("ERROR", SOURCE, "ui input failed: " + SdkRuntime.describe(t));
        }
    }

    /**
     * The runtime is stopping: publishes {@link UiSnapshot#down} so the adapter takes every piece of SDK UI off screen
     * now, before any plugin is disabled, and publishes nothing after it ({@link #publishNow} becomes a no-op).
     */
    void publishDown() {
        synchronized (publishLock) {
            UiSnapshot s;
            synchronized (lock) {
                down = true;
                publishPending = false;
                s = UiSnapshot.down(++version);
            }
            table.publish(s);
        }
        log.append("STATUS", SOURCE, "ui down");
    }

    /** Builds and publishes a snapshot now. The publish timer's task; also a test hook. */
    void publishNow() {
        synchronized (publishLock) {
            UiSnapshot s;
            synchronized (lock) {
                if (down) {
                    return; // the runtime is stopping: the down snapshot is the last one
                }
                publishPending = false;
                published = true;
                lastPublishAt = clock.uptimeMs();
                s = build(++version);
            }
            table.publish(s);
        }
    }

    // ---- internals ----

    private void dispatchUnguarded(UiInput in) throws Exception {
        if (in == null) {
            return;
        }
        int kind = in.kind();
        String key = in.pageKey();
        if (key == null) {
            return;
        }
        if (kind == UiInput.ROW_SELECTED) {
            String target = rowTarget(key);
            if (target != null) {
                requestOpen(target);
            } else {
                dropped(key, "no such row");
            }
            return;
        }
        if (kind == UiInput.PAGE_SHOWN || kind == UiInput.PAGE_CLOSED) {
            String parent = parentKey(key); // a choice page's events are its parent's
            pageShown(parent, kind == UiInput.PAGE_SHOWN, parent.length() < key.length());
            return;
        }
        if (kind != UiInput.ENTRY_SELECTED && kind != UiInput.TOGGLE_REQUESTED && kind != UiInput.VALUE_REQUESTED) {
            return;
        }
        if (key.startsWith(SDK_OWNER + "/")) {
            Listener<UiInput> h;
            synchronized (lock) {
                h = sdkHandler;
            }
            if (h != null) {
                h.on(in);
            }
            return;
        }
        String pageKey = parentKey(key);
        PageRec rec;
        synchronized (lock) {
            rec = livePage(pageKey);
        }
        if (rec == null) {
            dropped(key, "no live page");
            return;
        }
        boolean onChoicePage = pageKey.length() < key.length();
        UiEntry entry = entry(rec.page, onChoicePage ? key.substring(pageKey.length() + 1) : in.entryKey());
        if (entry == null) {
            return;
        }
        if (!entry.enabled()) {
            dropped(key, "disabled");
            return;
        }
        final UiAction action;
        if (onChoicePage) {
            int i = index(in.entryKey());
            if (kind != UiInput.ENTRY_SELECTED || entry.kind() != UiEntry.CHOICE || i < 0 || i >= entry.optionCount()) {
                return;
            }
            action = new UiAction(rec.pageId, entry.key(), null, new Integer(i), null);
        } else if (kind == UiInput.ENTRY_SELECTED && entry.kind() == UiEntry.LINK) {
            boolean live;
            synchronized (lock) {
                live = livePage(entry.targetPageKey()) != null;
            }
            if (live) { // a target cancelled since: open nothing
                requestOpen(entry.targetPageKey());
            }
            return;
        } else if (kind == UiInput.ENTRY_SELECTED && entry.kind() == UiEntry.ITEM) {
            action = new UiAction(rec.pageId, entry.key(), null);
        } else if (kind == UiInput.TOGGLE_REQUESTED && entry.kind() == UiEntry.TOGGLE) {
            action = new UiAction(rec.pageId, entry.key(), in.on() ? Boolean.TRUE : Boolean.FALSE);
        } else if (kind == UiInput.VALUE_REQUESTED && entry.kind() == UiEntry.DROPDOWN) {
            if (in.value() < 0 || in.value() >= entry.optionCount()) {
                return;
            }
            action = new UiAction(rec.pageId, entry.key(), null, new Integer(in.value()), null);
        } else if (kind == UiInput.VALUE_REQUESTED && entry.kind() == UiEntry.SLIDER) {
            Runnable hook = sliderResolvedHook;
            if (hook != null) {
                hook.run();
            }
            submitSlider(rec, pageKey, entry, snap(entry, in.value()));
            return;
        } else {
            return; // PROGRESS, INFO, a CHOICE row (the adapter opens its page), a kind mismatch
        }
        submit(rec, action);
    }

    private static void submit(PageRec rec, final UiAction action) {
        final Listener<UiAction> l = rec.listener;
        if (l == null) {
            return;
        }
        // On the plugin's executor, inside its catch-all: a throw counts toward quarantine like any callback.
        rec.exec.submit("ui:" + rec.pageId, new Runnable() {
            public void run() {
                try {
                    l.on(action);
                } catch (Exception e) {
                    EventBus.<RuntimeException>sneakyThrow(e);
                }
            }
        });
    }

    /**
     * At most one SLIDER action per {@code pendingKey} waits on the plugin's executor: a value arriving while one
     * waits replaces its value; the task reads the latest value as it starts.
     */
    private void submitSlider(PageRec rec, String pageKey, UiEntry entry, int value) {
        final Listener<UiAction> l = rec.listener;
        if (l == null) {
            return;
        }
        final String pendingKey = choiceKey(pageKey, entry.key()); // <pageKey>#<itemId>, as a choice page's key
        final PendingSlider p;
        synchronized (lock) {
            PageRec live = livePage(pageKey);
            if (live == null || live.exec != rec.exec) {
                return; // its owner was removed since rec was resolved: a holder now would outlive the plugin
            }
            PendingSlider waiting = (PendingSlider) pendingSliders.get(pendingKey);
            if (waiting != null) {
                waiting.value = value;
                return;
            }
            p = new PendingSlider();
            p.value = value;
            pendingSliders.put(pendingKey, p);
        }
        final String pageId = rec.pageId;
        final String itemId = entry.key();
        boolean queued = rec.exec.submit("ui:" + pageId, new Runnable() {
            public void run() {
                int v;
                synchronized (lock) {
                    if (pendingSliders.get(pendingKey) != p) {
                        return; // its owner was removed meanwhile
                    }
                    pendingSliders.remove(pendingKey);
                    v = p.value;
                }
                try {
                    l.on(new UiAction(pageId, itemId, null, new Integer(v), null));
                } catch (Exception e) {
                    EventBus.<RuntimeException>sneakyThrow(e);
                }
            }
        });
        if (!queued) {
            synchronized (lock) {
                if (pendingSliders.get(pendingKey) == p) {
                    pendingSliders.remove(pendingKey);
                }
            }
        }
    }

    /**
     * {@code v} clamped to the slider's range and snapped to the nearest {@code min + k * step}; a tie goes toward the
     * slider's current value.
     */
    private static int snap(UiEntry e, int v) {
        long min = e.min();
        long max = e.max();
        long step = e.step() < 1 ? 1 : e.step();
        long x = v < min ? min : v > max ? max : v;
        long r = (x - min) % step;
        if (r == 0) {
            return (int) x;
        }
        long lo = x - r;
        long hi = lo + step > max ? lo : lo + step;
        long twice = 2 * r;
        long out = twice < step ? lo : twice > step ? hi : (e.number() > x ? hi : lo);
        return (int) out;
    }

    /** A choice page's entry key ({@code "0".."n-1"}) as its index; -1 for anything else. */
    private static int index(String entryKey) {
        if (entryKey == null || entryKey.length() == 0 || entryKey.length() > 2) {
            return -1;
        }
        int n = 0;
        for (int i = 0; i < entryKey.length(); i++) {
            char c = entryKey.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            n = n * 10 + (c - '0');
        }
        return String.valueOf(n).equals(entryKey) ? n : -1; // no leading zeros: exactly the keys published
    }

    /** The page a key belongs to: {@code <pageKey>} for a choice page {@code <pageKey>#<itemId>}, else the key. */
    private static String parentKey(String key) {
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }

    private String rowTarget(String rowKey) {
        if (PLUGINS_KEY.equals(rowKey)) {
            return PLUGINS_KEY;
        }
        synchronized (lock) {
            for (Iterator it = owners.values().iterator(); it.hasNext();) {
                UiRow r = (UiRow) ((Owner) it.next()).rows.get(rowKey);
                if (r != null) {
                    return livePage(r.pageKey()) != null ? r.pageKey() : null; // its page was cancelled: hidden
                }
            }
        }
        return null;
    }

    /**
     * {@code key} is a page key; {@code choicePage} when the event was its choice page's. Closing a choice page never
     * drops the parent's stopped notice: the pop that follows a stop must still land on it.
     */
    private void pageShown(String key, boolean shown, boolean choicePage) {
        boolean changed = false;
        synchronized (lock) {
            if (shown) {
                markOpen(key);
                shownPageKey = key;
            } else {
                if (key.equals(shownPageKey)) {
                    shownPageKey = null;
                }
                openTitles.remove(key);
                changed = !choicePage && notices.remove(key) != null;
            }
        }
        if (changed) {
            changed();
        }
    }

    /** Logs a dropped input once per page key. */
    private void dropped(String key, String why) {
        synchronized (lock) {
            if (dropLogged.contains(key) || dropLogged.size() >= MAX_TRACKED_KEYS) {
                return;
            }
            dropLogged.add(key);
        }
        log.append("INFO", SOURCE, "ui input for " + UiText.clean(key, 80) + " dropped: " + why);
    }

    /** Lock held. The owner's page for {@code key}, or null. */
    private PageRec livePage(String key) {
        int slash = key.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        Owner o = owners.get(key.substring(0, slash));
        return o == null ? null : (PageRec) o.pages.get(key.substring(slash + 1));
    }

    private static UiEntry entry(UiPage page, String entryKey) {
        if (entryKey == null) {
            return null;
        }
        List<UiEntry> entries = page.entries();
        for (int i = 0; i < entries.size(); i++) {
            UiEntry e = (UiEntry) entries.get(i);
            if (entryKey.equals(e.key())) {
                return e;
            }
        }
        return null;
    }

    /** Lock held. Remembers {@code key} as on screen, with its current title. */
    private void markOpen(String key) {
        String title = titleOf(key);
        openTitles.remove(key);
        openTitles.put(key, title != null ? title : (String) openTitles.get(key));
        if (openTitles.size() > MAX_TRACKED_KEYS) { // stale keys of a misbehaving adapter: forget the oldest
            Iterator it = openTitles.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    /** Lock held. A page was (re)registered: it replaces any notice under its key; track its title if open. */
    private void pageChanged(UiPage page) {
        notices.remove(page.key());
        dropLogged.remove(page.key());
        if (openTitles.containsKey(page.key())) {
            openTitles.put(page.key(), page.title());
        }
    }

    /** Lock held. The title of the page now registered under {@code key}, or null. */
    private String titleOf(String key) {
        PageRec rec = livePage(key);
        if (rec != null) {
            return rec.page.title();
        }
        for (int i = 0; i < sdkPages.size(); i++) {
            UiPage p = (UiPage) sdkPages.get(i);
            if (key.equals(p.key())) {
                return p.title();
            }
        }
        return null;
    }

    /**
     * Lock held. Cleans the spec into a {@link UiPage}; resolves each LINK target to a page key the same
     * owner has registered, through the SDK's own handle (never a call on a plugin-supplied object).
     */
    private UiPage toUiPage(String owner, Owner o, PageSpec spec) {
        List<PageSpec.Entry> in = spec.entries();
        List<UiEntry> out = new ArrayList<UiEntry>(in.size());
        String pageKey = owner + "/" + spec.id();
        for (int i = 0; i < in.size(); i++) {
            PageSpec.Entry e = (PageSpec.Entry) in.get(i);
            PageSpec.Kind k = e.kind();
            String id = e.itemId();
            String label = UiText.clean(e.label(), k == PageSpec.Kind.INFO ? PageSpec.MAX_INFO : PageSpec.MAX_LABEL);
            UiEntry u;
            if (k == PageSpec.Kind.ITEM) {
                u = new UiEntry(UiEntry.ITEM, id, label,
                        e.value() == null ? null : UiText.clean(e.value(), PageSpec.MAX_VALUE), false, null)
                        .withIcon(itemIcon(id, e.icon()));
            } else if (k == PageSpec.Kind.TOGGLE) {
                u = new UiEntry(UiEntry.TOGGLE, id, label, null, e.on(), null);
            } else if (k == PageSpec.Kind.INFO) {
                u = new UiEntry(UiEntry.INFO, null, label, null, false, null);
            } else if (k == PageSpec.Kind.LINK) {
                u = new UiEntry(UiEntry.LINK, id, label, null, false, linkTarget(owner, o, id, e.target()));
            } else if (k == PageSpec.Kind.CHOICE) {
                u = new UiEntry(UiEntry.CHOICE, id, label, null, false, choiceKey(pageKey, id))
                        .withOptions(cleanOptions(e.options())).withNumber(e.number());
            } else if (k == PageSpec.Kind.DROPDOWN) {
                u = new UiEntry(UiEntry.DROPDOWN, id, label, null, false, null)
                        .withOptions(cleanOptions(e.options())).withNumber(e.number());
            } else if (k == PageSpec.Kind.SLIDER) {
                u = new UiEntry(UiEntry.SLIDER, id, label, null, false, null).withNumber(e.number())
                        .withRange(e.min(), e.max(), e.step(), e.centred());
            } else if (k == PageSpec.Kind.PROGRESS) {
                u = new UiEntry(UiEntry.PROGRESS, id, label, null, false, null).withNumber(e.number());
            } else {
                throw new IllegalArgumentException("entry '" + id + "': unknown kind " + k);
            }
            out.add(e.enabled() ? u : u.withEnabled(false));
        }
        return new UiPage(pageKey, UiText.clean(spec.title(), PageSpec.MAX_TITLE), out);
    }

    private static String[] cleanOptions(String[] options) {
        String[] out = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            out[i] = UiText.clean(options[i], PageSpec.MAX_VALUE);
        }
        return out;
    }

    /** An item icon: only the SDK's own handle, read through its field (null for an inert one), as LINK targets. */
    private static UiIcon itemIcon(String itemId, Icon icon) {
        if (icon == null) {
            return null;
        }
        if (!(icon instanceof UiImpl.IconHandle)) {
            throw new IllegalArgumentException("item '" + itemId + "' must use an icon from ui().icon()/stockIcon()");
        }
        return ((UiImpl.IconHandle) icon).icon;
    }

    /**
     * One page per CHOICE entry of {@code page}: key {@link #choiceKey}, the entry's label as title, and a RADIO per
     * option (keys {@code "0".."n-1"}, on for the selected one).
     */
    private static List<UiPage> choicePages(UiPage page) {
        List<UiPage> out = null;
        List<UiEntry> entries = page.entries();
        for (int i = 0; i < entries.size(); i++) {
            UiEntry e = (UiEntry) entries.get(i);
            if (e.kind() != UiEntry.CHOICE) {
                continue;
            }
            List<UiEntry> radios = new ArrayList<UiEntry>(e.optionCount());
            for (int j = 0; j < e.optionCount(); j++) {
                radios.add(new UiEntry(UiEntry.RADIO, String.valueOf(j), e.option(j), null, j == e.number(), null));
            }
            if (out == null) {
                out = new ArrayList<UiPage>();
            }
            out.add(new UiPage(e.targetPageKey(), UiText.clean(e.label(), PageSpec.MAX_TITLE), radios));
        }
        return out == null ? Collections.<UiPage>emptyList() : out;
    }


    private static String linkTarget(String owner, Owner o, String itemId, Page target) {
        if (!(target instanceof UiImpl.PageHandle)) {
            throw new IllegalArgumentException("link '" + itemId + "' must target a page from ui().page()");
        }
        UiImpl.PageHandle h = (UiImpl.PageHandle) target;
        if (!owner.equals(h.owner()) || h.isCancelled() || o == null || !o.pages.containsKey(h.pageId())) {
            throw new IllegalArgumentException("link '" + itemId + "' must target a live page of this plugin");
        }
        return owner + "/" + h.pageId();
    }

    /** Lock held. */
    private UiSnapshot build(long v) {
        List<UiRow> rows = new ArrayList<UiRow>();
        rows.add(new UiRow(PLUGINS_KEY, pluginsRowLabel, PLUGINS_KEY));
        Map<String, UiPage> pages = new HashMap<String, UiPage>(notices);
        for (int i = 0; i < sdkPages.size(); i++) {
            UiPage p = (UiPage) sdkPages.get(i);
            pages.put(p.key(), p);
        }
        Map<String, Owner> status = new HashMap<String, Owner>();
        for (Iterator e = owners.entrySet().iterator(); e.hasNext();) {
            Map.Entry entry = (Map.Entry) e.next();
            Owner o = (Owner) entry.getValue();
            for (Iterator p = o.pages.values().iterator(); p.hasNext();) {
                PageRec rec = (PageRec) p.next();
                pages.put(rec.page.key(), rec.page);
                for (int i = 0; i < rec.choicePages.size(); i++) {
                    UiPage c = (UiPage) rec.choicePages.get(i);
                    pages.put(c.key(), c);
                }
            }
            for (Iterator r = o.rows.values().iterator(); r.hasNext();) {
                UiRow row = (UiRow) r.next();
                if (pages.containsKey(row.pageKey())) { // a row whose page was cancelled is hidden
                    rows.add(row);
                }
            }
            if (o.status != null && !statusHidden.contains(entry.getKey())) {
                status.put((String) entry.getKey(), o);
            }
        }
        List<String> order = StatusLine.order(statusOrder, status.keySet());
        List<StatusLine.Item> items = new ArrayList<StatusLine.Item>();
        for (int i = 0; i < order.size(); i++) {
            String id = order.get(i);
            Owner o = status.get(id);
            items.add(new StatusLine.Item(id, o.status, o.statusIcon));
        }
        // one item list, two outputs: the parts with icons, and the text-only line (the items without them)
        return new UiSnapshot(v, rows, StatusLine.line(indicators, items, statusMaxChars), pages, openSeq,
                openPageKey, StatusLine.parts(indicators, items, statusMaxChars));
    }

    /** Asks for one publish, at the earliest publishMinMs after the last one; a pending one absorbs this change. */
    private void changed() {
        long delay;
        synchronized (lock) {
            if (publishPending) {
                return;
            }
            publishPending = true;
            delay = published ? Math.max(0, lastPublishAt + publishMinMs - clock.uptimeMs()) : 0;
        }
        try {
            publishTimer.after(delay, publishTask);
        } catch (Throwable t) {
            synchronized (lock) {
                publishPending = false;
            }
            log.append("ERROR", SOURCE, "ui publish not scheduled: " + SdkRuntime.describe(t));
        }
    }

    private static void checkOwner(String owner) {
        if (owner == null || SDK_OWNER.equals(owner)) {
            throw new IllegalArgumentException("owner '" + owner + "' is reserved");
        }
    }
}
