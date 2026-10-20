package mibsdk.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import mibsdk.api.PageSpec;
import mibsdk.api.SdkInfo;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiText;

/**
 * The SDK's own pages (owner {@code mibsdk}): the Plugins menu, one page per plugin and the SDK page; and the
 * mapping of input on them to runtime actions. Pure functions: every text, including plugin-supplied names, is
 * cleaned and cut to the {@link PageSpec} limits here. LINK entries are navigated by the adapter; only toggles and
 * the reset item reach {@link #handle}.
 */
final class PluginsMenu {

    static final String PLUGINS_KEY = UiHub.PLUGINS_KEY;
    static final String SDK_KEY = UiHub.SDK_OWNER + "/sdk";
    static final String PLUGIN_KEY_PREFIX = UiHub.SDK_OWNER + "/plugin.";
    static final String ENABLED_ENTRY = "enabled";
    static final String RESET_ENTRY = "reset";

    /** What an input on an SDK page asks the runtime to do. */
    static final class Action {
        enum Kind {
            ENABLE, DISABLE, RESET_BOOT_GUARD, NONE
        }

        static final Action NONE = new Action(Kind.NONE, null);
        static final Action RESET_BOOT_GUARD = new Action(Kind.RESET_BOOT_GUARD, null);

        final Kind kind;
        /** The plugin id for ENABLE/DISABLE, else null. */
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
     * {@code pluginPageKeys}: each plugin's registered page keys; {@code g}: what each plugin was granted.
     */
    static List<UiPage> pages(List<PluginStatus> st, Map<String, PluginManifest> m,
            Map<String, List<String>> pluginPageKeys, SdkState s, Grants g) {
        List<UiPage> out = new ArrayList<UiPage>();
        out.add(root(st, m, s));
        for (int i = 0; i < st.size(); i++) {
            PluginStatus p = st.get(i);
            List<String> keys = pluginPageKeys == null ? null : pluginPageKeys.get(p.id());
            out.add(pluginPage(p, m == null ? null : m.get(p.id()), keys, s, g));
        }
        out.add(sdkPage(s));
        return out;
    }

    /** ENABLE/DISABLE for the Enabled toggle of a plugin page, RESET_BOOT_GUARD for the SDK page's item. */
    static Action handle(UiInput in) {
        if (in == null || in.pageKey() == null || in.entryKey() == null) {
            return Action.NONE;
        }
        String key = in.pageKey();
        if (in.kind() == UiInput.TOGGLE_REQUESTED && key.startsWith(PLUGIN_KEY_PREFIX)
                && ENABLED_ENTRY.equals(in.entryKey())) {
            String id = key.substring(PLUGIN_KEY_PREFIX.length());
            if (!ManifestParser.isValidId(id)) {
                return Action.NONE;
            }
            return new Action(in.on() ? Action.Kind.ENABLE : Action.Kind.DISABLE, id);
        }
        if (in.kind() == UiInput.ENTRY_SELECTED && SDK_KEY.equals(key) && RESET_ENTRY.equals(in.entryKey())) {
            return Action.RESET_BOOT_GUARD;
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
        int room = PageSpec.MAX_ENTRIES - e.size() - 1; // the SDK link always fits
        for (int i = 0; i < st.size() && i < room; i++) {
            PluginStatus p = st.get(i);
            e.add(link("plugin." + p.id(), name(p, m == null ? null : m.get(p.id())), stateWord(p.state()),
                    PLUGIN_KEY_PREFIX + p.id()));
        }
        e.add(link("sdk", "SDK", null, SDK_KEY));
        return page(PLUGINS_KEY, "Plugins", e);
    }

    private static UiPage pluginPage(PluginStatus p, PluginManifest m, List<String> pageKeys, SdkState s, Grants g) {
        List<UiEntry> e = new ArrayList<UiEntry>();
        if (m != null) {
            String author = m.author();
            e.add(info("v" + m.version() + (author == null || author.length() == 0 ? "" : " by " + author)));
        }
        PluginState state = p.state();
        if (isBlocked(state)) {
            e.add(info("Blocked: " + p.reason()));
        } else {
            e.add(new UiEntry(UiEntry.TOGGLE, ENABLED_ENTRY, "Enabled", null, state == PluginState.ENABLED, null));
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
        if (pageKeys != null) {
            for (int i = 0; i < pageKeys.size() && e.size() < PageSpec.MAX_ENTRIES; i++) {
                String key = pageKeys.get(i);
                String pageId = key.substring(key.indexOf('/') + 1);
                e.add(link("page." + i, pageId, null, key));
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
