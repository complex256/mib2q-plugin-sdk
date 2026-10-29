package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mibsdk.api.OverrideScope;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginsMenuTest {

    @TempDir
    File tmp;

    private static final SdkState NORMAL = state(false, null, false);

    private static SdkState state(boolean safe, String reason, boolean resetRequested) {
        return new SdkState(safe, reason, false, resetRequested, "hqc", Collections.<ActiveOverride>emptyList());
    }

    private static PluginManifest manifest(String id, String name, String... perms) {
        return new PluginManifest(id, name, "1.2", "Ann", 2, "x.Main", Arrays.asList(perms),
                Collections.<String>emptyList());
    }

    private static PluginStatus status(String id, PluginState s, String reason) {
        return new PluginStatus(id, "1.2", s, reason);
    }

    private Grants grants(String text) throws IOException {
        File f = new File(tmp, "grants.properties");
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(text.getBytes("ISO-8859-1"));
        } finally {
            out.close();
        }
        return Grants.load(f);
    }

    private static List<UiPage> pages(List<PluginStatus> st, Map<String, PluginManifest> m,
            Map<String, List<String>> keys, SdkState s, Grants g) {
        return PluginsMenu.pages(st, m, keys, null, Collections.<String>emptySet(), s, g);
    }

    private static UiPage page(List<UiPage> pages, String key) {
        for (UiPage p : pages) {
            if (p.key().equals(key)) {
                return p;
            }
        }
        return null;
    }

    private static List<String> labels(UiPage p, int kind) {
        List<String> out = new ArrayList<String>();
        for (UiEntry e : p.entries()) {
            if (e.kind() == kind) {
                out.add(e.label());
            }
        }
        return out;
    }

    private static UiEntry entry(UiPage p, String key) {
        for (UiEntry e : p.entries()) {
            if (key.equals(e.key())) {
                return e;
            }
        }
        return null;
    }

    @Test
    void rootListsEveryPluginWithStateWord() throws Exception {
        List<PluginStatus> st = Arrays.asList(status("a", PluginState.ENABLED, "enabled"),
                status("b", PluginState.DISABLED, "off in Plugins menu"), status("c", PluginState.FAILED, "boom"),
                status("d", PluginState.QUARANTINED, "hung in onEnable"),
                status("e", PluginState.BLOCKED_PERMISSIONS, "missing permissions: x"),
                status("f", PluginState.BLOCKED_INVALID, "bad json"));
        Map<String, PluginManifest> m = new HashMap<String, PluginManifest>();
        for (String id : new String[] {"a", "b", "c", "d", "e"}) {
            m.put(id, manifest(id, "Name " + id.toUpperCase()));
        }
        List<UiPage> pages = pages(st, m, null, NORMAL, grants(""));

        UiPage root = pages.get(0);
        assertEquals("mibsdk/plugins", root.key());
        assertEquals("Plugins", root.title());
        assertEquals(7, root.entries().size(), "six plugins and the SDK link, no header outside safe mode");
        String[] words = {"On", "Off", "Failed", "Quarantined", "Blocked", "Blocked"};
        String[] names = {"Name A", "Name B", "Name C", "Name D", "Name E", "f"};
        for (int i = 0; i < 6; i++) {
            UiEntry e = root.entries().get(i);
            assertEquals(UiEntry.LINK, e.kind());
            assertEquals(names[i], e.label());
            assertEquals(words[i], e.value());
            assertEquals("mibsdk/plugin." + st.get(i).id(), e.targetPageKey());
            assertNotNull(page(pages, e.targetPageKey()), "the link target is one of the pages");
        }
        UiEntry sdk = root.entries().get(6);
        assertEquals("SDK", sdk.label());
        assertEquals("mibsdk/sdk", sdk.targetPageKey());
        assertEquals("mibsdk/sdk", pages.get(pages.size() - 1).key());
        assertEquals(8, pages.size());

        UiPage a = page(pages, "mibsdk/plugin.a");
        assertEquals("Name A", a.title());
        assertEquals("v1.2 by Ann", a.entries().get(0).label());
        assertTrue(entry(a, "enabled").on());
        UiPage c = page(pages, "mibsdk/plugin.c");
        assertTrue(entry(c, "enabled").on(), "the toggle shows the user's choice (on), not the state");
        assertTrue(labels(c, UiEntry.INFO).contains("Stopped: boom"));
        assertTrue(labels(page(pages, "mibsdk/plugin.d"), UiEntry.INFO).contains("Stopped: hung in onEnable"));
        assertEquals("f", page(pages, "mibsdk/plugin.f").title(), "no manifest: the id is the name");
    }

    @Test
    void blockedPluginHasNoToggleAndShowsReason() throws Exception {
        List<PluginStatus> st = Collections.singletonList(
                status("e", PluginState.BLOCKED_PERMISSIONS, "missing permissions: vehicle.read"));
        Map<String, PluginManifest> m = Collections.singletonMap("e", manifest("e", "Eve", "vehicle.read"));
        UiPage e = page(pages(st, m, null, NORMAL, grants("")), "mibsdk/plugin.e");
        assertTrue(labels(e, UiEntry.TOGGLE).isEmpty());
        assertTrue(labels(e, UiEntry.INFO).contains("Blocked: missing permissions: vehicle.read"));
        assertTrue(labels(e, UiEntry.INFO).contains("Permissions: none"));
    }

    @Test
    void pluginPageListsGrantedPermissionsOnly() throws Exception {
        List<PluginStatus> st = Collections.singletonList(status("a", PluginState.ENABLED, "enabled"));
        Map<String, PluginManifest> m = Collections.singletonMap("a",
                manifest("a", "A", "vehicle.read", "vehicle.parking.write", "ui.slot.carmenu"));
        UiPage a = page(pages(st, m, null, NORMAL, grants("a=ui.slot.carmenu,vehicle.read,net\n")),
                "mibsdk/plugin.a");
        assertTrue(labels(a, UiEntry.INFO).contains("Permissions: vehicle.read, ui.slot.carmenu"));
    }

    @Test
    void safeModeHeaderAndSdkPage() throws Exception {
        SdkState safe = new SdkState(true, "unhealthy-boots", true, false, "hqc",
                Arrays.asList(new ActiveOverride("parking.soundDirections", "pdc", OverrideScope.PERSISTENT,
                        "F1R0L1X1", true)));
        List<PluginStatus> st = Collections.singletonList(status("a", PluginState.DISABLED, "x"));
        List<UiPage> pages = pages(st, null, null, safe, grants(""));
        UiPage root = pages.get(0);
        assertEquals(UiEntry.INFO, root.entries().get(0).kind());
        assertEquals("SDK safe mode: plugins are off (unhealthy-boots)", root.entries().get(0).label());
        assertTrue(labels(page(pages, "mibsdk/plugin.a"), UiEntry.INFO).contains("Safe mode: changes apply after it ends"));

        UiPage sdk = page(pages, "mibsdk/sdk");
        assertEquals("SDK", sdk.title());
        List<String> info = labels(sdk, UiEntry.INFO);
        assertEquals("API 6 · adapter hqc", info.get(0));
        assertEquals("Safe mode: on (unhealthy-boots)", info.get(1));
        assertEquals("Boot guard: inert", info.get(2));
        assertTrue(info.contains("Override: parking.soundDirections=F1R0L1X1 by pdc (PERSISTENT, restoring)"), info.toString());

        List<String> normal = labels(page(pages(st, null, null, NORMAL, grants("")), "mibsdk/sdk"),
                UiEntry.INFO);
        assertEquals("Safe mode: off", normal.get(1));
        assertEquals("Boot guard: ok", normal.get(2));
        assertFalse(labels(page(pages(st, null, null, NORMAL, grants("")), "mibsdk/plugin.a"),
                UiEntry.INFO).contains("Safe mode: changes apply after it ends"));
        for (String s : normal) {
            assertFalse(s.toLowerCase().indexOf("latch") >= 0, "no latch line: under the latch the UI is off");
        }
    }

    @Test
    void resetItemOnlyForCountBasedSafeMode() throws Exception {
        List<PluginStatus> none = Collections.<PluginStatus>emptyList();
        UiPage counted = page(pages(none, null, null, state(true, "unhealthy-boots", false), grants("")),
                "mibsdk/sdk");
        UiEntry reset = entry(counted, "reset");
        assertNotNull(reset);
        assertEquals(UiEntry.ITEM, reset.kind());
        assertEquals("Reset boot guard", reset.label());

        UiPage done = page(pages(none, null, null, state(true, "unhealthy-boots", true), grants("")),
                "mibsdk/sdk");
        assertNull(entry(done, "reset"));
        assertTrue(labels(done, UiEntry.INFO).contains("Takes effect at the next start"));

        UiPage marker = page(pages(none, null, null, state(true, "marker", false), grants("")),
                "mibsdk/sdk");
        assertNull(entry(marker, "reset"));
        assertTrue(labels(marker, UiEntry.INFO).contains("SAFE_MODE marker set: clear it with mibsdk-dev safe-mode off"));

        UiPage normal = page(pages(none, null, null, NORMAL, grants("")), "mibsdk/sdk");
        assertNull(entry(normal, "reset"));
        assertFalse(labels(normal, UiEntry.INFO).contains("SAFE_MODE marker set: clear it with mibsdk-dev safe-mode off"));
    }

    @Test
    void sdkPageOffersDwellAndAnimationRows() throws Exception {
        UiPage sdk = page(pages(Collections.<PluginStatus>emptyList(), null, null, NORMAL, grants("")), "mibsdk/sdk");
        assertEquals("Dwell: 5 s", entry(sdk, "statusbar.dwell").label());
        assertEquals("Animation: 300 ms", entry(sdk, "statusbar.animate").label());
        assertEquals(PluginsMenu.Action.Kind.DWELL_NEXT,
                PluginsMenu.handle(new UiInput(UiInput.ENTRY_SELECTED, "mibsdk/sdk", "statusbar.dwell", false)).kind);
        assertEquals(PluginsMenu.Action.Kind.ANIMATE_NEXT,
                PluginsMenu.handle(new UiInput(UiInput.ENTRY_SELECTED, "mibsdk/sdk", "statusbar.animate", false)).kind);
        assertEquals(PluginsMenu.Action.Kind.NONE,
                PluginsMenu.handle(new UiInput(UiInput.TOGGLE_REQUESTED, "mibsdk/sdk", "statusbar.dwell", true)).kind);
    }

    @Test
    void toggleInputMapsToEnableDisable() {
        PluginsMenu.Action on = PluginsMenu.handle(new UiInput(UiInput.TOGGLE_REQUESTED, "mibsdk/plugin.rack", "enabled", true));
        assertEquals(PluginsMenu.Action.Kind.ENABLE, on.kind);
        assertEquals("rack", on.id);
        PluginsMenu.Action off = PluginsMenu.handle(new UiInput(UiInput.TOGGLE_REQUESTED, "mibsdk/plugin.rack", "enabled", false));
        assertEquals(PluginsMenu.Action.Kind.DISABLE, off.kind);
        assertEquals("rack", off.id);
        assertEquals(PluginsMenu.Action.Kind.RESET_BOOT_GUARD,
                PluginsMenu.handle(new UiInput(UiInput.ENTRY_SELECTED, "mibsdk/sdk", "reset", false)).kind);

        UiInput[] ignored = {
            new UiInput(UiInput.ENTRY_SELECTED, "mibsdk/plugin.rack", "enabled", false),
            new UiInput(UiInput.TOGGLE_REQUESTED, "mibsdk/plugin.rack", "other", true),
            new UiInput(UiInput.TOGGLE_REQUESTED, "mibsdk/plugin.bad id!", "enabled", true),
            new UiInput(UiInput.TOGGLE_REQUESTED, "rack/plugin.rack", "enabled", true),
            new UiInput(UiInput.ENTRY_SELECTED, "mibsdk/plugins", "plugin.rack", false),
            new UiInput(UiInput.TOGGLE_REQUESTED, "mibsdk/sdk", "reset", true),
            new UiInput(UiInput.PAGE_SHOWN, "mibsdk/sdk", null, false),
            null,
        };
        for (UiInput in : ignored) {
            assertEquals(PluginsMenu.Action.Kind.NONE, PluginsMenu.handle(in).kind);
        }
    }

    @Test
    void pluginWhoseRowOverflowsIsStillLinked() throws Exception {
        List<PluginStatus> st = new ArrayList<PluginStatus>();
        Map<String, PluginManifest> m = new HashMap<String, PluginManifest>();
        Map<String, List<String>> keys = new HashMap<String, List<String>>();
        for (int i = 1; i <= 9; i++) {
            String id = "p" + i;
            st.add(status(id, PluginState.ENABLED, "enabled"));
            m.put(id, manifest(id, "Plugin " + i));
            keys.put(id, Collections.singletonList(id + "/main"));
        }
        List<UiPage> pages = pages(st, m, keys, NORMAL, grants(""));
        UiPage root = pages.get(0);
        List<String> links = labels(root, UiEntry.LINK);
        assertEquals(10, links.size(), "9 plugins (8 Car row slots) plus SDK");
        for (int i = 1; i <= 9; i++) {
            UiPage p = page(pages, "mibsdk/plugin.p" + i);
            UiEntry link = p.entries().get(p.entries().size() - 1);
            assertEquals(UiEntry.LINK, link.kind());
            assertEquals("main", link.label());
            assertEquals("p" + i + "/main", link.targetPageKey());
        }
    }

    @Test
    void hostileManifestTextIsCleanedAndCut() throws Exception {
        String longName = "N\u0007ame ‮" + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        List<PluginStatus> st = Collections.singletonList(status("a", PluginState.FAILED, "r\nr"));
        Map<String, PluginManifest> m = Collections.singletonMap("a", manifest("a", longName));
        List<UiPage> pages = pages(st, m, null, NORMAL, grants(""));
        UiEntry link = pages.get(0).entries().get(0);
        assertEquals(40, link.label().length());
        assertTrue(link.label().startsWith("N ame xxx"), link.label());
        assertTrue(link.label().endsWith("..."));
        assertEquals(32, page(pages, "mibsdk/plugin.a").title().length());
        assertTrue(labels(page(pages, "mibsdk/plugin.a"), UiEntry.INFO).contains("Stopped: r r"));
    }

    @Test
    void samePagesComparesContent() throws Exception {
        List<PluginStatus> st = Collections.singletonList(status("a", PluginState.ENABLED, "enabled"));
        List<UiPage> x = pages(st, null, null, NORMAL, grants(""));
        List<UiPage> y = pages(st, null, null, NORMAL, grants(""));
        assertTrue(PluginsMenu.samePages(x, y));
        List<UiPage> z = pages(Collections.singletonList(status("a", PluginState.DISABLED, "x")), null,
                null, NORMAL, grants(""));
        assertFalse(PluginsMenu.samePages(x, z));
        assertFalse(PluginsMenu.samePages(x, null));
    }

    // ---- fix round 1 ----

    @Test
    void toggleShowsTheUsersRecordedChoice() throws Exception {
        List<PluginStatus> st = Arrays.asList(status("a", PluginState.FAILED, "boom"),
                status("b", PluginState.ENABLED, "enabled"), status("c", PluginState.DISABLED, "off in Plugins menu"));
        Set<String> off = Collections.singleton("c");
        List<UiPage> pages = PluginsMenu.pages(st, null, null, null, off, NORMAL, grants(""));
        assertTrue(entry(page(pages, "mibsdk/plugin.a"), "enabled").on(), "failed, but the user left it on");
        assertTrue(entry(page(pages, "mibsdk/plugin.b"), "enabled").on());
        assertFalse(entry(page(pages, "mibsdk/plugin.c"), "enabled").on());
    }

    @Test
    void blockedOnASwitchedOffDependencyKeepsItsToggle() throws Exception {
        List<PluginStatus> st = Arrays.asList(status("child", PluginState.BLOCKED_DEPENDENCY, "dependency base is DISABLED"),
                status("orphan", PluginState.BLOCKED_DEPENDENCY, "missing dependency base"));
        List<UiPage> pages = pages(st, null, null, NORMAL, grants(""));
        UiPage child = page(pages, "mibsdk/plugin.child");
        assertNotNull(entry(child, "enabled"));
        assertTrue(labels(child, UiEntry.INFO).contains("Blocked: dependency base is DISABLED"));
        assertNull(entry(page(pages, "mibsdk/plugin.orphan"), "enabled"));
    }

    @Test
    void rootOverflowEndsWithMoreEntry() throws Exception {
        List<PluginStatus> st = new ArrayList<PluginStatus>();
        for (int i = 0; i < 30; i++) {
            st.add(status("p" + (i < 10 ? "0" : "") + i, PluginState.ENABLED, "enabled"));
        }
        UiPage root = pages(st, null, null, NORMAL, grants("")).get(0);
        assertEquals(24, root.entries().size());
        assertEquals(22, labels(root, UiEntry.LINK).size() - 1, "22 plugins and the SDK link");
        UiEntry more = root.entries().get(22);
        assertEquals(UiEntry.INFO, more.kind());
        assertEquals("+8 more", more.label());
        assertEquals("SDK", root.entries().get(23).label());
        assertEquals(8, PluginsMenu.rootOverflow(30, NORMAL));
        assertEquals(0, PluginsMenu.rootOverflow(23, NORMAL), "23 plugins and SDK fit exactly");
        assertEquals(2, PluginsMenu.rootOverflow(23, state(true, "marker", false)), "the header takes a slot: 21 links + more");
    }

    @Test
    void pluginPageLinksUseThePageTitle() throws Exception {
        List<PluginStatus> st = Collections.singletonList(status("a", PluginState.ENABLED, "enabled"));
        Map<String, List<String>> keys = Collections.singletonMap("a", Arrays.asList("a/main", "a/cfg"));
        Map<String, String> titles = Collections.singletonMap("a/main", "Rack status");
        UiPage a = page(PluginsMenu.pages(st, null, keys, titles, Collections.<String>emptySet(), NORMAL, grants("")),
                "mibsdk/plugin.a");
        assertEquals(Arrays.asList("Rack status", "cfg"), labels(a, UiEntry.LINK), "title, else the page id");
    }

    // ---- status item controls ----

    private static List<String> lines(UiPage p) {
        List<String> out = new ArrayList<String>();
        for (UiEntry e : p.entries()) {
            out.add(mibsdk.platform.UiPageText.line(e));
        }
        return out;
    }

    private static List<UiPage> withStatus(List<PluginStatus> st, List<String> eligible, Set<String> hidden)
            throws IOException {
        return PluginsMenu.pages(st, null, null, null, Collections.<String>emptySet(), NORMAL, null,
                new PluginsMenu.StatusChoices(eligible, hidden));
    }

    @Test
    void statusRowsOnlyForPluginsThatCanShowAnItem() throws Exception {
        List<PluginStatus> st = Arrays.asList(status("rack", PluginState.ENABLED, "enabled"),
                status("plain", PluginState.ENABLED, "enabled"), status("ts", PluginState.ENABLED, "enabled"));
        List<UiPage> p = withStatus(st, Arrays.asList("ts", "rack"), Collections.singleton("rack"));
        assertEquals(Arrays.asList("[on] Enabled", "[off] Status item (2 of 2)", "Move status item earlier",
                "Move status item later"), lines(page(p, "mibsdk/plugin.rack")));
        assertEquals(Arrays.asList("[on] Enabled", "[on] Status item (1 of 2)", "Move status item earlier",
                "Move status item later"), lines(page(p, "mibsdk/plugin.ts")));
        assertEquals(Arrays.asList("[on] Enabled"), lines(page(p, "mibsdk/plugin.plain")), "no item, no permission");

        List<UiPage> alone = withStatus(st, Arrays.asList("ts"), Collections.<String>emptySet());
        assertEquals(Arrays.asList("[on] Enabled", "[on] Status item"), lines(page(alone, "mibsdk/plugin.ts")),
                "nothing to reorder with one item");
        assertEquals(Arrays.asList("[on] Enabled"), lines(page(pages(st, null, null, NORMAL, null),
                "mibsdk/plugin.ts")), "no status choices given: no rows");
    }

    @Test
    void statusInputMapsToStatusActions() {
        PluginsMenu.Action hide = PluginsMenu.handle(new UiInput(UiInput.TOGGLE_REQUESTED, "mibsdk/plugin.rack",
                "status", false));
        assertEquals(PluginsMenu.Action.Kind.STATUS_HIDE, hide.kind);
        assertEquals("rack", hide.id);
        assertEquals(PluginsMenu.Action.Kind.STATUS_SHOW, PluginsMenu.handle(new UiInput(UiInput.TOGGLE_REQUESTED,
                "mibsdk/plugin.rack", "status", true)).kind);
        PluginsMenu.Action earlier = PluginsMenu.handle(new UiInput(UiInput.ENTRY_SELECTED, "mibsdk/plugin.rack",
                "status.earlier", false));
        assertEquals(PluginsMenu.Action.Kind.STATUS_EARLIER, earlier.kind);
        assertEquals("rack", earlier.id);
        assertEquals(PluginsMenu.Action.Kind.STATUS_LATER, PluginsMenu.handle(new UiInput(UiInput.ENTRY_SELECTED,
                "mibsdk/plugin.rack", "status.later", false)).kind);
        assertEquals(PluginsMenu.Action.Kind.NONE, PluginsMenu.handle(new UiInput(UiInput.ENTRY_SELECTED,
                "mibsdk/plugin.Bad!", "status.later", false)).kind, "invalid id");
        assertEquals(PluginsMenu.Action.Kind.NONE, PluginsMenu.handle(new UiInput(UiInput.ENTRY_SELECTED,
                "mibsdk/sdk", "status.later", false)).kind, "only on a plugin page");
    }
}
