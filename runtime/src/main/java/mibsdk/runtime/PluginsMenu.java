package mibsdk.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mibsdk.api.PageSpec;
import mibsdk.api.SdkInfo;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiText;

/**
 * The SDK's own pages (owner {@code mibsdk}): the Plugins menu, one page per plugin and the SDK page; and the
 * mapping of input on them to runtime actions. Pure functions: every text, including plugin-supplied names, is
 * cleaned and cut to the {@link PageSpec} limits here. LINK entries are navigated by the adapter; only toggles, the
 * status move items and the reset item reach {@link #handle}. Toggles render as native checkboxes on the HMI
 * ({@code [on]}/{@code [off]} text only in FakeUi) and the move items are plain knob-selectable rows.
 */
final class PluginsMenu {

    static final String PLUGINS_KEY = UiHub.PLUGINS_KEY;
    static final String SDK_KEY = UiHub.SDK_OWNER + "/sdk";
    static final String PLUGIN_KEY_PREFIX = UiHub.SDK_OWNER + "/plugin.";
    static final String ENABLED_ENTRY = "enabled";
    static final String RESET_ENTRY = "reset";
    static final String STATUS_ENTRY = "status";
    static final String STATUS_EARLIER_ENTRY = "status.earlier";
    static final String STATUS_LATER_ENTRY = "status.later";
    /** The SDK page's built-in bar settings: on/off, dwell between rows and slide duration. */
    static final String BAR_ENABLED_ENTRY = "statusbar.enabled";
    static final String DWELL_ENTRY = "statusbar.dwell";
    static final String ANIMATE_ENTRY = "statusbar.animate";

    /**
     * What the plugin pages need for the status-item rows: {@code eligible}, the plugins that have a status item or
     * the permission for one, in the line's order ({@link StatusLine#order}); {@code hidden}, those the user hid.
     */
    static final class StatusChoices {
        final List<String> eligible;
        final Set<String> hidden;

        StatusChoices(List<String> eligible, Set<String> hidden) {
            this.eligible = eligible == null ? Collections.<String>emptyList() : eligible;
            this.hidden = hidden == null ? Collections.<String>emptySet() : hidden;
        }
    }

    /** What an input on an SDK page asks the runtime to do. */
    static final class Action {
        enum Kind {
            ENABLE, DISABLE, RESET_BOOT_GUARD, STATUS_SHOW, STATUS_HIDE, STATUS_EARLIER, STATUS_LATER,
            DWELL_NEXT, ANIMATE_NEXT, STATUS_BAR_ENABLE, STATUS_BAR_DISABLE, NONE
        }

        static final Action NONE = new Action(Kind.NONE, null);
        static final Action RESET_BOOT_GUARD = new Action(Kind.RESET_BOOT_GUARD, null);

        final Kind kind;
        /** The plugin id for ENABLE/DISABLE and the STATUS_* kinds, else null. */
        final String id;

        Action(Kind kind, String id) {
            this.kind = kind;
            this.id = id;
        }

        @Override
        public String toString() {
            return id == null ? kind.toString() : kind + "(" + id + ")";
        }
    }

    private PluginsMenu() {
    }

    /**
     * {@code mibsdk/plugins}, then {@code mibsdk/plugin.<id>} for every plugin in {@code st}, then
     * {@code mibsdk/sdk}. {@code m}: manifests by id (absent for an unreadable plugin.json);
     * {@code pluginPageKeys}: each plugin's registered page keys; {@code pageTitles}: their titles by page key (a
     * link falls back to the page id); {@code offIds}: the plugins the user switched off (the toggles show this
     * choice, not whether the plugin runs); {@code g}: what each plugin was granted. Any map may be null.
     */
    static List<UiPage> pages(List<PluginStatus> st, Map<String, PluginManifest> m,
            Map<String, List<String>> pluginPageKeys, Map<String, String> pageTitles, Set<String> offIds, SdkState s,
            Grants g) {
        return pages(st, m, pluginPageKeys, pageTitles, offIds, s, g, null);
    }

    /**
     * As above, and each plugin page of a plugin in {@code sc.eligible} gets a {@code Status item} toggle (on: shown;
     * with its place, {@code (2 of 3)}, when there are several) and, with two or more eligible plugins, the
     * {@code Move status item earlier} / {@code later} items. {@code sc} null: no status rows.
     */
    static List<UiPage> pages(List<PluginStatus> st, Map<String, PluginManifest> m,
            Map<String, List<String>> pluginPageKeys, Map<String, String> pageTitles, Set<String> offIds, SdkState s,
            Grants g, StatusChoices sc) {
        List<UiPage> out = new ArrayList<UiPage>();
        out.add(root(st, m, s));
        for (int i = 0; i < st.size(); i++) {
            PluginStatus p = st.get(i);
            List<String> keys = pluginPageKeys == null ? null : pluginPageKeys.get(p.id());
            boolean off = offIds != null && offIds.contains(p.id());
            out.add(pluginPage(p, m == null ? null : m.get(p.id()), keys, pageTitles, off, s, g, sc));
        }
        out.add(sdkPage(s));
        return out;
    }

    /** How many of {@code plugins} the Plugins page cannot list (it then ends with a {@code +N more} INFO). */
    static int rootOverflow(int plugins, SdkState s) {
        int room = PageSpec.MAX_ENTRIES - (s.safeMode ? 1 : 0) - 1; // header, SDK link
        return plugins <= room ? 0 : plugins - (room - 1); // one more slot for "+N more"
    }

    /**
     * True if the plugin page has the Enabled toggle: not blocked, or blocked only because a dependency is switched
     * off ({@code dependency <id> is DISABLED}), so the user can still say what should run once it is back.
     */
    static boolean hasToggle(PluginState state, String reason) {
        if (!isBlocked(state)) {
            return true;
        }
        return state == PluginState.BLOCKED_DEPENDENCY && reason != null && reason.startsWith("dependency ")
                && reason.endsWith(" is " + PluginState.DISABLED);
    }

    /**
     * On a plugin page: ENABLE/DISABLE for the Enabled toggle, STATUS_SHOW/STATUS_HIDE for the Status item toggle,
     * STATUS_EARLIER/STATUS_LATER for the move items. On the SDK page: RESET_BOOT_GUARD for the reset item,
     * DWELL_NEXT/ANIMATE_NEXT for the built-in bar's dwell and animation rows, STATUS_BAR_ENABLE/DISABLE for its
     * on/off toggle.
     */
    static Action handle(UiInput in) {
        if (in == null || in.pageKey() == null || in.entryKey() == null) {
            return Action.NONE;
        }
        String key = in.pageKey();
        String entry = in.entryKey();
        if (key.startsWith(PLUGIN_KEY_PREFIX)) {
            String id = key.substring(PLUGIN_KEY_PREFIX.length());
            Action.Kind kind = null;
            if (in.kind() == UiInput.TOGGLE_REQUESTED && ENABLED_ENTRY.equals(entry)) {
                kind = in.on() ? Action.Kind.ENABLE : Action.Kind.DISABLE;
            } else if (in.kind() == UiInput.TOGGLE_REQUESTED && STATUS_ENTRY.equals(entry)) {
                kind = in.on() ? Action.Kind.STATUS_SHOW : Action.Kind.STATUS_HIDE;
            } else if (in.kind() == UiInput.ENTRY_SELECTED && STATUS_EARLIER_ENTRY.equals(entry)) {
                kind = Action.Kind.STATUS_EARLIER;
            } else if (in.kind() == UiInput.ENTRY_SELECTED && STATUS_LATER_ENTRY.equals(entry)) {
                kind = Action.Kind.STATUS_LATER;
            }
            return kind == null || !ManifestParser.isValidId(id) ? Action.NONE : new Action(kind, id);
        }
        if (in.kind() == UiInput.TOGGLE_REQUESTED && SDK_KEY.equals(key) && BAR_ENABLED_ENTRY.equals(entry)) {
            return in.on() ? new Action(Action.Kind.STATUS_BAR_ENABLE, null)
                    : new Action(Action.Kind.STATUS_BAR_DISABLE, null);
        }
        if (in.kind() == UiInput.ENTRY_SELECTED && SDK_KEY.equals(key)) {
            if (RESET_ENTRY.equals(entry)) {
                return Action.RESET_BOOT_GUARD;
            }
            if (DWELL_ENTRY.equals(entry)) {
                return new Action(Action.Kind.DWELL_NEXT, null);
            }
            if (ANIMATE_ENTRY.equals(entry)) {
                return new Action(Action.Kind.ANIMATE_NEXT, null);
            }
        }
        return Action.NONE;
    }

    /** {@code On}, {@code Off}, {@code Failed}, {@code Quarantined} or {@code Blocked}. */
    static String stateWord(PluginState state) {
        if (state == PluginState.ENABLED) {
            return "On";
        }
        if (state == PluginState.FAILED) {
            return "Failed";
        }
        if (state == PluginState.QUARANTINED) {
            return "Quarantined";
        }
        if (isBlocked(state)) {
            return "Blocked";
        }
        return "Off"; // DISABLED, or DISCOVERED (not enabled yet)
    }

    /** True if both lists hold the same pages with the same content, in the same order. */
    static boolean samePages(List<UiPage> a, List<UiPage> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return a == b;
        }
        for (int i = 0; i < a.size(); i++) {
            UiPage x = a.get(i);
            UiPage y = b.get(i);
            if (!same(x.key(), y.key()) || !same(x.title(), y.title()) || x.entries().size() != y.entries().size()) {
                return false;
            }
            for (int k = 0; k < x.entries().size(); k++) {
                UiEntry e = x.entries().get(k);
                UiEntry f = y.entries().get(k);
                if (e.kind() != f.kind() || e.on() != f.on() || !same(e.key(), f.key()) || !same(e.label(), f.label())
                        || !same(e.value(), f.value()) || !same(e.targetPageKey(), f.targetPageKey())) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- pages ----

    private static UiPage root(List<PluginStatus> st, Map<String, PluginManifest> m, SdkState s) {
        List<UiEntry> e = new ArrayList<UiEntry>();
        if (s.safeMode) {
            e.add(info("SDK safe mode: plugins are off (" + s.safeReason + ")"));
        }
        int hidden = rootOverflow(st.size(), s);
        for (int i = 0; i < st.size() - hidden; i++) {
            PluginStatus p = st.get(i);
            e.add(link("plugin." + p.id(), name(p, m == null ? null : m.get(p.id())), stateWord(p.state()),
                    PLUGIN_KEY_PREFIX + p.id()));
        }
        if (hidden > 0) {
            e.add(info("+" + hidden + " more"));
        }
        e.add(link("sdk", "SDK", null, SDK_KEY));
        return page(PLUGINS_KEY, "Plugins", e);
    }

    private static UiPage pluginPage(PluginStatus p, PluginManifest m, List<String> pageKeys,
            Map<String, String> pageTitles, boolean off, SdkState s, Grants g, StatusChoices sc) {
        List<UiEntry> e = new ArrayList<UiEntry>();
        if (m != null) {
            String author = m.author();
            e.add(info("v" + m.version() + (author == null || author.length() == 0 ? "" : " by " + author)));
        }
        PluginState state = p.state();
        if (isBlocked(state)) {
            e.add(info("Blocked: " + p.reason()));
        }
        if (hasToggle(state, p.reason())) {
            e.add(new UiEntry(UiEntry.TOGGLE, ENABLED_ENTRY, "Enabled", null, !off, null));
            if (s.safeMode) {
                e.add(info("Safe mode: changes apply after it ends"));
            }
        }
        if (state == PluginState.FAILED || state == PluginState.QUARANTINED) {
            e.add(info("Stopped: " + p.reason()));
        }
        if (m != null) {
            e.add(info("Permissions: " + granted(m, g)));
        }
        int place = sc == null ? -1 : sc.eligible.indexOf(p.id());
        if (place >= 0) {
            int n = sc.eligible.size();
            String label = n > 1 ? "Status item (" + (place + 1) + " of " + n + ")" : "Status item";
            e.add(new UiEntry(UiEntry.TOGGLE, STATUS_ENTRY, label, null, !sc.hidden.contains(p.id()), null));
            if (n > 1) {
                e.add(new UiEntry(UiEntry.ITEM, STATUS_EARLIER_ENTRY, "Move status item earlier", null, false, null));
                e.add(new UiEntry(UiEntry.ITEM, STATUS_LATER_ENTRY, "Move status item later", null, false, null));
            }
        }
        if (pageKeys != null) {
            for (int i = 0; i < pageKeys.size() && e.size() < PageSpec.MAX_ENTRIES; i++) {
                String key = pageKeys.get(i);
                String title = pageTitles == null ? null : pageTitles.get(key);
                String label = title == null || title.trim().length() == 0 ? key.substring(key.indexOf('/') + 1) : title;
                e.add(link("page." + i, label, null, key));
            }
        }
        return page(PLUGIN_KEY_PREFIX + p.id(), name(p, m), e);
    }

    private static UiPage sdkPage(SdkState s) {
        List<UiEntry> e = new ArrayList<UiEntry>();
        e.add(info("API " + SdkInfo.API_VERSION + " · adapter " + s.adapterId));
        e.add(info("Safe mode: " + (s.safeMode ? "on (" + s.safeReason + ")" : "off")));
        e.add(info("Boot guard: " + (s.guardInert ? "inert" : "ok")));
        if (s.safeMode && !s.resetOffered()) {
            e.add(info("SAFE_MODE marker set: clear it with mibsdk-dev safe-mode off"));
        } else if (s.resetOffered() && s.resetRequested) {
            e.add(info("Takes effect at the next start"));
        } else if (s.resetOffered()) {
            e.add(new UiEntry(UiEntry.ITEM, RESET_ENTRY, "Reset boot guard", null, false, null));
        }
        e.add(new UiEntry(UiEntry.TOGGLE, BAR_ENABLED_ENTRY, "Status bar", null, s.statusBarEnabled, null));
        e.add(new UiEntry(UiEntry.ITEM, DWELL_ENTRY, "Dwell: " + StatusBarConfig.dwellLabel(s.dwellMs), null, false,
                null));
        e.add(new UiEntry(UiEntry.ITEM, ANIMATE_ENTRY, "Animation: " + StatusBarConfig.animateLabel(s.animateMs), null,
                false, null));
        for (int i = 0; i < s.overrides.size() && e.size() < PageSpec.MAX_ENTRIES; i++) {
            e.add(info("Override: " + s.overrides.get(i)));
        }
        return page(SDK_KEY, "SDK", e);
    }

    // ---- helpers ----

    /** The manifest name, else the id. */
    private static String name(PluginStatus p, PluginManifest m) {
        String n = m == null ? null : m.name();
        return n == null || n.trim().length() == 0 ? p.id() : n;
    }

    /** The manifest's permissions that are granted, in manifest order, comma-separated; {@code none} if empty. */
    private static String granted(PluginManifest m, Grants g) {
        List<String> out = new ArrayList<String>();
        List<String> declared = m.permissions();
        for (int i = 0; i < declared.size(); i++) {
            String perm = declared.get(i);
            if (g != null && g.granted(m.id()).contains(perm) && !out.contains(perm)) {
                out.add(perm);
            }
        }
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < out.size(); i++) {
            b.append(i > 0 ? ", " : "").append(out.get(i));
        }
        return b.length() == 0 ? "none" : b.toString();
    }

    private static boolean isBlocked(PluginState s) {
        return s == PluginState.BLOCKED_INVALID || s == PluginState.BLOCKED_PERMISSIONS
                || s == PluginState.BLOCKED_DEPENDENCY;
    }

    private static UiEntry info(String text) {
        return new UiEntry(UiEntry.INFO, null, UiText.clean(text, PageSpec.MAX_INFO), null, false, null);
    }

    private static UiEntry link(String key, String label, String value, String target) {
        return new UiEntry(UiEntry.LINK, key, UiText.clean(label, PageSpec.MAX_LABEL),
                value == null ? null : UiText.clean(value, PageSpec.MAX_VALUE), false, target);
    }

    private static UiPage page(String key, String title, List<UiEntry> entries) {
        return new UiPage(key, UiText.clean(title, PageSpec.MAX_TITLE), Collections.unmodifiableList(entries));
    }

    private static boolean same(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
