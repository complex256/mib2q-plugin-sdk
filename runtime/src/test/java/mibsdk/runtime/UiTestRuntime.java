package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.api.Features;
import mibsdk.platform.HookTable;
import mibsdk.platform.SdkRoots;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiSnapshot;
import mibsdk.platform.UiTable;

/** A real {@link SdkRuntime} on a {@link FakePlatform} with the UI features, for the UI hub tests. */
final class UiTestRuntime {

    static final Set<String> UI_FEATURES = new HashSet<String>(
            Arrays.asList(Features.SLOT_CAR_MENU, Features.SLOT_STATUS_BAR, Features.UI_PAGES));

    final FakeClock clock = new FakeClock();
    final UiTable table = new UiTable(64);
    final FakePlatform fake;
    private final File base;
    private final File plugins;
    private final SdkRoots roots;
    private final File tmp;
    private final PluginJars jars;
    private final Map<String, String[]> grants = new LinkedHashMap<String, String[]>();
    SdkRuntime rt;

    UiTestRuntime(File tmp, Set<String> features) {
        this.tmp = tmp;
        this.fake = new FakePlatform("HMI_TEST", features);
        this.roots = FakePlatform.roots(tmp);
        this.base = roots.install();
        this.plugins = new File(base, "plugins");
        plugins.mkdirs();
        this.jars = new PluginJars(new File(tmp, "fixtures"));
    }

    /** {@code fx.Main} whose onEnable runs {@code onEnableBody}; manifest name {@code name}; all perms granted. */
    void plugin(String id, String name, String[] perms, String onEnableBody, String members) throws IOException {
        String source = "package fx;\n"
                + "import mibsdk.api.*;\n"
                + "public class Main extends MibPlugin {\n"
                + "  PluginContext ctx;\n"
                + (members == null ? "" : members) + "\n"
                + "  protected void onEnable(final PluginContext ctx) throws Exception {\n"
                + "    this.ctx = ctx;\n"
                + onEnableBody + "\n"
                + "  }\n"
                + "}\n";
        StringBuilder p = new StringBuilder("[");
        for (int i = 0; i < perms.length; i++) {
            p.append(i > 0 ? "," : "").append('"').append(perms[i]).append('"');
        }
        p.append(']');
        String json = "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"version\":\"1\",\"api\":2,"
                + "\"main\":\"fx.Main\",\"permissions\":" + p + ",\"dependencies\":[]}";
        jars.writeRaw(new File(plugins, id + ".jar"), source, json);
        grants.put(id, perms);
    }

    /** Forces safe mode at the next start. */
    void safeModeMarker() throws IOException {
        write(new File(base, "SAFE_MODE"), "");
    }

    /** The writable persistent root (boot.state, plugins.properties). */
    File persist() {
        return roots.persist();
    }

    /** Writes {@code <persist>/boot.state} before the next start. */
    void bootState(String text) throws IOException {
        write(new File(roots.persist(), "boot.state"), text);
    }

    SdkRuntime start() throws IOException {
        SerialExecutor.watchdogPeriodMs = 10;
        write(new File(base, "sdk.properties"), "plugin.start.delay.ms=0\nheap.low.bytes=0\nui.publish.min.ms=0\n"
                + "log.file=" + new File(tmp, "mibsdk.log").getPath() + "\n");
        StringBuilder g = new StringBuilder();
        for (Map.Entry<String, String[]> e : grants.entrySet()) {
            g.append(e.getKey()).append('=');
            String[] perms = e.getValue();
            for (int i = 0; i < perms.length; i++) {
                g.append(i > 0 ? "," : "").append(perms[i]);
            }
            g.append('\n');
        }
        write(new File(base, "grants.properties"), g.toString());
        rt = new SdkRuntime(RuntimeConfig.load(roots), fake, clock, new HookTable(64), table, new RingLog(500));
        rt.start();
        return rt;
    }

    /** Stops the runtime off-thread so a wedged runtime fails rather than hangs; resets the watchdog period. */
    void stop() throws InterruptedException {
        try {
            if (rt != null) {
                final SdkRuntime r = rt;
                Thread t = new Thread() {
                    public void run() {
                        r.stop();
                    }
                };
                t.setDaemon(true);
                t.start();
                t.join(15000);
            }
        } finally {
            SerialExecutor.watchdogPeriodMs = 1000;
        }
    }

    UiSnapshot await(Predicate<UiSnapshot> p, String what) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            UiSnapshot s = table.snapshot();
            if (p.test(s)) {
                return s;
            }
            Thread.sleep(10);
        }
        fail("snapshot never " + what + "\n" + (rt == null ? "" : rt.log().snapshot()));
        return null;
    }

    void awaitState(String id, PluginState state) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            for (PluginStatus s : rt.statuses()) {
                if (s.id().equals(id) && s.state() == state) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        fail(id + " never reached " + state + ": " + rt.statuses() + "\n" + rt.log().snapshot());
    }

    /** Log lines from {@code source} ending with {@code msg}. */
    int count(String source, String msg) {
        int n = 0;
        for (String line : rt.log().snapshot()) {
            if (line.endsWith(" " + source + " " + msg)) {
                n++;
            }
        }
        return n;
    }

    void awaitCount(String source, String msg, int n) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            if (count(source, msg) >= n) {
                return;
            }
            Thread.sleep(10);
        }
        fail("expected " + n + " x '" + source + " " + msg + "' in\n" + rt.log().snapshot());
    }

    /** True if any entry label or title in the snapshot contains {@code s}. */
    static boolean mentions(UiSnapshot snap, String s) {
        for (UiPage p : snap.pages().values()) {
            if (p.title() != null && p.title().indexOf(s) >= 0) {
                return true;
            }
            for (UiEntry e : p.entries()) {
                if (e.label() != null && e.label().indexOf(s) >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }
}
