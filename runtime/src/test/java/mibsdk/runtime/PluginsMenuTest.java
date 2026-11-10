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
import mibsdk.api.WifiStatus;
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
        assertTrue(root.ring(), "the Plugins page is a ring");
        List<String> ring = labels(root, UiEntry.LINK);
        assertEquals(Arrays.asList("Installed", "Updates", "Available", "Networking", "Settings"), ring,
                "the ring's five items");
        assertEquals("mibsdk/installed", entry(root, "installed").targetPageKey());
        assertEquals("mibsdk/updates", entry(root, "updates").targetPageKey());
        assertEquals("mibsdk/available", entry(root, "available").targetPageKey());
        assertEquals("mibsdk/wifi", entry(root, "networking").targetPageKey());
        assertEquals("mibsdk/sdk", entry(root, "settings").targetPageKey());
        for (int i = 0; i < root.entries().size(); i++) {
            assertNotNull(root.entries().get(i).icon(), "ring row " + i + " has its glyph");
        }

        UiPage installed = page(pages, "mibsdk/installed");
        assertEquals("Installed", installed.title());
        assertFalse(installed.ring());
        String[] words = {"On", "Off", "Failed", "Quarantined", "Blocked", "Blocked"};
        String[] names = {"Name A", "Name B", "Name C", "Name D", "Name E", "f"};
        assertEquals(6, installed.entries().size());
        for (int i = 0; i < 6; i++) {
            UiEntry e = installed.entries().get(i);
            assertEquals(UiEntry.LINK, e.kind());
            assertEquals(names[i], e.label());
            assertEquals(words[i], e.value());
            assertEquals("mibsdk/plugin." + st.get(i).id(), e.targetPageKey());
            assertNotNull(page(pages, e.targetPageKey()), "the link target is one of the pages");
        }
        assertEquals("Settings", page(pages, "mibsdk/sdk").title());
        assertEquals("mibsdk/wifi", page(pages, "mibsdk/wifi").key());
        assertEquals(12, pages.size());

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
    void wifiPageShowsDhcpLeaseDetails() throws Exception {
        WifiConfig cfg = new WifiConfig(true, "the valley", "pw", true, "", "", "", "");
        WifiView wifi = new WifiView(true, true, false, WifiStatus.CONNECTED, "the valley", "192.168.141.125", "",
                "the valley", Collections.<mibsdk.api.WifiNetwork>emptyList(), cfg, "255.255.0.0", "192.168.50.1",
                "192.168.10.11");
        SdkState s = new SdkState(false, null, false, false, "hqc", Collections.<ActiveOverride>emptyList(), 4000, 300,
                true, wifi);
        UiPage p = page(pages(Collections.<PluginStatus>emptyList(), null, null, s, grants("")), "mibsdk/wifi");
        assertNotNull(p);
        List<String> info = labels(p, UiEntry.INFO);
        assertTrue(info.contains("Address: automatic (DHCP)"), info.toString());
        assertTrue(info.contains("IP: 192.168.141.125"), info.toString());
        assertTrue(info.contains("Netmask: 255.255.0.0"), info.toString());
        assertTrue(info.contains("Gateway: 192.168.50.1"), info.toString());
        assertTrue(info.contains("DNS: 192.168.10.11"), info.toString());
    }

    @Test
    void safeModeHeaderAndSdkPage() throws Exception {
        SdkState safe = new SdkState(true, "unhealthy-boots", true, false, "hqc",
                Arrays.asList(new ActiveOverride("parking.soundDirections", "pdc", OverrideScope.PERSISTENT,
                        "F1R0L1X1", true)));
        List<PluginStatus> st = Collections.singletonList(status("a", PluginState.DISABLED, "x"));
        List<UiPage> pages = pages(st, null, null, safe, grants(""));
        UiPage root = pages.get(0);
        // the ring holds its five items; the safe-mode notice is on the Installed list
        assertEquals("Installed", root.entries().get(0).label());
        UiPage installed = page(pages, "mibsdk/installed");
        assertEquals(UiEntry.INFO, installed.entries().get(0).kind());
        assertEquals("SDK safe mode: plugins are off (unhealthy-boots)", installed.entries().get(0).label());
        assertTrue(labels(page(pages, "mibsdk/plugin.a"), UiEntry.INFO).contains("Safe mode: changes apply after it ends"));

        UiPage sdk = page(pages, "mibsdk/sdk");
        assertEquals("Settings", sdk.title());
        List<String> info = labels(sdk, UiEntry.INFO);
        assertEquals("API 8 · adapter hqc", info.get(0));
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
        UiEntry bar = entry(sdk, "statusbar.enabled");
        assertEquals(UiEntry.TOGGLE, bar.kind());
        assertEquals("Status bar", bar.label());
        assertTrue(bar.on(), "on by default");
        assertEquals(PluginsMenu.Action.Kind.STATUS_BAR_DISABLE,
                PluginsMenu.handle(new UiInput(UiInput.TOGGLE_REQUESTED, "mibsdk/sdk", "statusbar.enabled", false)).kind);
        assertEquals(PluginsMenu.Action.Kind.STATUS_BAR_ENABLE,
                PluginsMenu.handle(new UiInput(UiInput.TOGGLE_REQUESTED, "mibsdk/sdk", "statusbar.enabled", true)).kind);
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
        UiPage installed = page(pages, "mibsdk/installed");
        List<String> links = labels(installed, UiEntry.LINK);
        assertEquals(9, links.size(), "9 plugins, all listed");
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
        UiEntry link = page(pages, "mibsdk/installed").entries().get(0);
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
        List<UiPage> all = pages(st, null, null, NORMAL, grants(""));
        UiPage installed = page(all, "mibsdk/installed");
        assertEquals(24, installed.entries().size());
        assertEquals(23, labels(installed, UiEntry.LINK).size(), "23 plugins, then the notice");
        assertEquals("p00", installed.entries().get(0).label());
        UiEntry more = installed.entries().get(23);
        assertEquals(UiEntry.INFO, more.kind());
        assertEquals("+7 more", more.label());
        assertEquals(7, PluginsMenu.rootOverflow(30, NORMAL));
        assertEquals(0, PluginsMenu.rootOverflow(24, NORMAL), "24 plugins fit exactly");
        assertEquals(2, PluginsMenu.rootOverflow(25, NORMAL));
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

// ---- the ring's package pages (Updates, Available, one package) ----

/** A catalog as mibpkg writes it, from a temporary file. */
private static PackageCatalog catalog(String text) throws Exception {
    java.io.File f = java.io.File.createTempFile("mibpkg", ".catalog");
    f.deleteOnExit();
    java.nio.file.Files.write(f.toPath(), text.getBytes("UTF-8"));
    return PackageCatalog.load(f);
}

private static final String CATALOG_TEXT = "format=1\nupdated=1\ncount=3\n"
        + "pkg.bike-rack.desc=Bike Rack (mibsdk plugin)\n"
        + "pkg.bike-rack.kind=plugin\n"
        + "pkg.bike-rack.installed=1.1.0\n"
        + "pkg.bike-rack.update=1.2.0\n"
        + "pkg.bike-rack.updatefrom=complex256-mibpkg\n"
        + "pkg.bike-rack.avail=1.2.0\n"
        + "pkg.bike-rack.availfrom=complex256-mibpkg\n"
        + "pkg.bike-rack.availname=mibpkg\n"
        + "pkg.bike-rack.compatible=true\n"
        + "pkg.bike-rack.perms=vehicle.read,vehicle.parking.write\n"
        + "pkg.bike-rack.perm.vehicle.read=Read vehicle state\n"
        + "pkg.hello.desc=Sample plugin\n"
        + "pkg.hello.kind=plugin\n"
        + "pkg.hello.avail=1.0.0\n"
        + "pkg.hello.availfrom=complex256-mibpkg\n"
        + "pkg.hello.availname=mibpkg\n"
        + "pkg.hello.compatible=true\n"
        + "pkg.mibsdk.kind=sdk\n"
        + "pkg.mibsdk.installed=0.7.1\n";

@Test
void updatesAndAvailableListTheCatalog() throws Exception {
    SdkState s = new SdkState(false, null, false, false, "hqc", null, 5000, 300, true, WifiView.NONE,
            catalog(CATALOG_TEXT), null);
    List<UiPage> pages = pages(Collections.<PluginStatus>emptyList(), null, null, s, grants(""));

    UiPage root = pages.get(0);
    assertEquals("1 update", entry(root, "updates").value());
    assertEquals("1 package", entry(root, "available").value());

    UiPage updates = page(pages, "mibsdk/updates");
    assertEquals("Check for updates", updates.entries().get(0).label());
    assertEquals(UiEntry.ITEM, updates.entries().get(0).kind());
    UiEntry rack = entry(updates, "pkg.bike-rack");
    assertEquals("bike-rack", rack.label(), "the package id: the same on Updates and Available");
    assertEquals("1.1.0 -> 1.2.0", rack.value());
    assertEquals("mibsdk/pkg.bike-rack", rack.targetPageKey());

    UiPage available = page(pages, "mibsdk/available");
    assertEquals("hello", entry(available, "pkg.hello").label());
    assertEquals("1.0.0", entry(available, "pkg.hello").value());

    // one page per offered package; the installed SDK has none
    UiPage pkg = page(pages, "mibsdk/pkg.bike-rack");
    assertTrue(labels(pkg, UiEntry.INFO).contains("Installed: 1.1.0"));
    assertTrue(labels(pkg, UiEntry.INFO).contains("Update: 1.2.0 from mibpkg"));
    assertTrue(labels(pkg, UiEntry.INFO).contains("Permission: vehicle.read - Read vehicle state"));
    assertEquals("Update to 1.2.0", entry(pkg, "install").label());
    assertEquals(UiEntry.ITEM, entry(pkg, "install").kind());

    // installed and already on the offered version: no action row, "Up to date" instead
    SdkState same = new SdkState(false, null, false, false, "hqc", null, 5000, 300, true, WifiView.NONE,
            catalog(CATALOG_TEXT.replace("pkg.bike-rack.installed=1.1.0", "pkg.bike-rack.installed=1.2.0")
                    .replace("pkg.bike-rack.update=1.2.0\n", "")
                    .replace("pkg.bike-rack.updatefrom=complex256-mibpkg\n", "")), null);
    UiPage upToDate = page(pages(Collections.<PluginStatus>emptyList(), null, null, same, grants("")),
            "mibsdk/pkg.bike-rack");
    assertNull(entry(upToDate, "install"), "nothing to install");
    assertTrue(labels(upToDate, UiEntry.INFO).contains("Up to date"));
    assertNull(page(pages, "mibsdk/pkg.mibsdk"), "the SDK itself has no MMI package page");

    // the last package action's line shows on the pages it belongs to
    SdkState busy = new SdkState(false, null, false, false, "hqc", null, 5000, 300, true, WifiView.NONE,
            catalog(CATALOG_TEXT), "Checking for updates...");
    assertTrue(labels(page(pages(Collections.<PluginStatus>emptyList(), null, null, busy, grants("")),
            "mibsdk/updates"), UiEntry.INFO).contains("Checking for updates..."));
}

@Test
void packageRowsMapToCheckForUpdatesAndInstall() throws Exception {
    PluginsMenu.Action check = PluginsMenu.handle(
            new UiInput(UiInput.ENTRY_SELECTED, "mibsdk/updates", "check", false));
    assertEquals(PluginsMenu.Action.Kind.CHECK_UPDATES, check.kind);
    PluginsMenu.Action all = PluginsMenu.handle(
            new UiInput(UiInput.ENTRY_SELECTED, "mibsdk/available", "check", false));
    assertEquals(PluginsMenu.Action.Kind.CHECK_UPDATES, all.kind);

    PluginsMenu.Action install = PluginsMenu.handle(
            new UiInput(UiInput.ENTRY_SELECTED, "mibsdk/pkg.bike-rack", "install", false));
    assertEquals(PluginsMenu.Action.Kind.INSTALL, install.kind);
    assertEquals("bike-rack", install.id);
    assertEquals(PluginsMenu.Action.Kind.NONE, PluginsMenu.handle(
            new UiInput(UiInput.ENTRY_SELECTED, "mibsdk/pkg.not a name", "install", false)).kind);
    assertEquals(PluginsMenu.Action.Kind.NONE, PluginsMenu.handle(
            new UiInput(UiInput.ENTRY_SELECTED, "mibsdk/installed", "check", false)).kind);
}
}
