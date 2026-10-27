package mibsdk.test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.adapter.fake.FakeUi;
import mibsdk.api.Features;
import mibsdk.platform.HookTable;
import mibsdk.platform.SdkRoots;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiPage;
import mibsdk.platform.UiTable;
import mibsdk.runtime.Clock;
import mibsdk.runtime.ManifestException;
import mibsdk.runtime.ManifestParser;
import mibsdk.runtime.PluginManifest;
import mibsdk.runtime.PluginState;
import mibsdk.runtime.PluginStatus;
import mibsdk.runtime.RingLog;
import mibsdk.runtime.RuntimeConfig;
import mibsdk.runtime.SdkRuntime;

/**
 * Runs a built plugin jar on the real SDK runtime and a fake car, so a plugin's host tests need no setup code.
 *
 * <p>{@code start} copies the jar into a temporary "unit" under the test's temp directory, installs what the unit
 * would hold after the user installed the package (grants, icons), starts {@link SdkRuntime} on {@link FakePlatform}
 * and waits until the plugin is {@code ENABLED}. Drive the plugin through {@link #ui()} and {@link #car()}, move time
 * with {@link #advance}, and assert on what the UI shows and what the plugin stored.
 *
 * <pre>{@code
 * @Test
 * void startsEnabled(@TempDir File tmp) {
 *     try (PluginTestHarness h = PluginTestHarness.start(tmp)) {
 *         h.await(() -> !h.ui().render(h.pageKey("main")).isEmpty(), "the page");
 *     }
 * }
 * }</pre>
 *
 * <p>Where the files come from: the plugin jar is {@code -Dmibsdk.pluginJar} (or {@link Builder#jar}); the icon PNGs
 * are {@code <name>.png} in {@code -Dmibsdk.pluginIcons} (or {@link Builder#icons}), by default {@code src/main/icons}
 * of the project under test. The {@code mibsdk.plugin} Gradle plugin sets both properties for a plugin's tests.
 *
 * <p>What the "unit" is given, so a test sees the same thing every time:
 * <ul>
 * <li>{@code sdk.properties} is {@code plugin.start.delay.ms=0} (no start delay), {@code heap.low.bytes=0} (no heap
 * guard), {@code ui.publish.min.ms=0} (no UI throttle) and {@code log.file=<tmp>/mibsdk.log}.</li>
 * <li>{@code grants.properties} grants the permissions given to {@link Builder#grants}, by default every permission
 * the plugin's {@code plugin.json} declares.</li>
 * <li>The unit has the features given to {@link Builder#features}, by default every {@link Features} constant.</li>
 * <li>Icons are installed as mibpkg does: each icon in {@code plugin.json} is copied to {@code icons/<id>/<sha16>.png},
 * where sha16 is the first 16 lowercase hex characters of the PNG's SHA-256, with an {@code index.properties} line
 * {@code <name>=<sha16>,<width>,<height>}. {@link #iconSha} gives the sha16 for an assertion such as
 * {@code "[png:" + h.iconSha("cup") + "] Coffee"}.</li>
 * <li>The Scheduler's clock is the real uptime plus {@link #advance}.</li>
 * </ul>
 *
 * <p>After {@link #restart()} the harness has a new runtime, UI and car: call {@link #ui()} and {@link #car()} again.
 * Waits last 5 seconds. A harness is for one plugin on one thread; {@link #close()} stops the runtime.
 */
public final class PluginTestHarness implements AutoCloseable {

    private static final String[] ALL_FEATURES = {Features.UI_PAGES, Features.UI_INPUTS, Features.UI_ICONS,
            Features.SLOT_CAR_MENU, Features.SLOT_STATUS_BAR, Features.PARKING, Features.IGNITION,
            Features.HOOK_PARKING};
    private static final long AWAIT_MS = 5000;

    private final File tmp;
    private final File jar;
    private final PluginManifest manifest;
    private final Set<String> features;
    private final Map<String, String> iconShas = new LinkedHashMap<String, String>();
    /** Added to the real uptime: raising it moves the plugin's Scheduler forward. */
    private final AtomicLong skew = new AtomicLong();

    private SdkRuntime rt;
    private boolean running;
    private FakePlatform car;
    private UiTable table;
    private FakeUi ui;
    private RingLog log;

    private PluginTestHarness(File tmp, File jar, File icons, Set<String> features, List<String> grants) {
        this.tmp = tmp;
        this.jar = jar;
        this.features = features;
        this.manifest = readManifest(jar);
        install(icons, grants);
    }

    /** A harness for the jar in {@code -Dmibsdk.pluginJar} and the icons in {@code -Dmibsdk.pluginIcons}. */
    public static Builder builder(File tmp) {
        return new Builder(tmp);
    }

    /** {@code builder(tmp).start()}. */
    public static PluginTestHarness start(File tmp) {
        return builder(tmp).start();
    }

    public static final class Builder {
        private final File tmp;
        private File jar;
        private File icons;
        private String[] features;
        private String[] grants;

        private Builder(File tmp) {
            this.tmp = tmp;
        }

        /** The features the fake unit has; none when called with no arguments. Default: every {@link Features} constant. */
        public Builder features(String... features) {
            this.features = features.clone();
            return this;
        }

        /** The permissions the user granted; none when called with no arguments. Default: every one plugin.json declares. */
        public Builder grants(String... permissions) {
            this.grants = permissions.clone();
            return this;
        }

        /** The plugin jar, instead of {@code -Dmibsdk.pluginJar}. */
        public Builder jar(File jar) {
            this.jar = jar;
            return this;
        }

        /** The directory with the {@code <name>.png} icons, instead of {@code -Dmibsdk.pluginIcons} or {@code src/main/icons}. */
        public Builder icons(File dir) {
            this.icons = dir;
            return this;
        }

        /** Starts the plugin and waits until it is {@code ENABLED}; fails with the runtime log if it never is. */
        public PluginTestHarness start() {
            PluginTestHarness h = create();
            h.begin(true);
            return h;
        }

        /**
         * Starts the plugin and waits until it is {@code FAILED}, {@code QUARANTINED} or {@code BLOCKED_*}; does not throw
         * for that. Read the outcome with {@link #state()}. Still fails if the plugin ends up enabled, or never settles.
         */
        public PluginTestHarness startExpectingFailure() {
            PluginTestHarness h = create();
            h.begin(false);
            return h;
        }

        private PluginTestHarness create() {
            File j = jar != null ? jar : fileProperty("mibsdk.pluginJar",
                    "the plugin jar: pass -Dmibsdk.pluginJar=<path to the built jar> or call Builder.jar(File)");
            File i = icons != null ? icons : defaultIcons();
            Set<String> f = new HashSet<String>(Arrays.asList(features != null ? features : ALL_FEATURES));
            return new PluginTestHarness(tmp, j, i, f, grants == null ? null : Arrays.asList(grants));
        }

        private static File defaultIcons() {
            String p = System.getProperty("mibsdk.pluginIcons");
            return p != null ? new File(p) : new File("src/main/icons");
        }

        private static File fileProperty(String name, String what) {
            String p = System.getProperty(name);
            if (p == null) {
                throw new IllegalStateException("Missing " + what + " (system property " + name + " is not set)");
            }
            return new File(p);
        }
    }

    // ---- the plugin ----

    /** The plugin's id from its plugin.json. */
    public String pluginId() {
        return manifest.id();
    }

    /** The key of one of the plugin's pages for {@link FakeUi}: {@code "<id>/<pageId>"}. */
    public String pageKey(String pageId) {
        return manifest.id() + "/" + pageId;
    }

    /** The plugin's lifecycle state by {@code PluginState} name, e.g. {@code "ENABLED"}; {@code "NONE"} if the runtime lists no plugin. */
    public String state() {
        PluginStatus s = status();
        return s == null ? "NONE" : s.state().name();
    }

    /** The first 16 hex characters of the SHA-256 of icon {@code name}: the key a {@code [png:<key>]} tag shows. */
    public String iconSha(String name) {
        String sha = iconShas.get(name);
        if (sha == null) {
            throw new IllegalArgumentException("No icon '" + name + "' installed; plugin.json declares " + iconShas.keySet());
        }
        return sha;
    }

    // ---- the fake unit ----

    /** What the user sees and presses: Car rows, the status line, pages, dialogs and toasts. New after {@link #restart()}. */
    public FakeUi ui() {
        return ui;
    }

    /** The fake car: ignition, parking and the ECU. New after {@link #restart()}. */
    public FakePlatform car() {
        return car;
    }

    /** Moves the plugin's Scheduler clock forward by {@code ms}; due tasks run on the plugin's own thread soon after. */
    public void advance(long ms) {
        skew.addAndGet(ms);
    }

    /** Waits up to 5 seconds for {@code condition}; fails with {@code what}, the plugin's pages and the runtime log. */
    public void await(BooleanSupplier condition, String what) {
        await(condition, what, AWAIT_MS);
    }

    void await(BooleanSupplier condition, String what, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1000000L;
        do {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("never " + what + "\n" + describe());
    }

    /**
     * Opens the plugin's page {@code pageId} as a user does: through the link on the plugin's Plugins-menu page. Fails
     * if that link never appears or the page does not open. Pages that show dialogs need to be on screen first.
     */
    public void openPage(String pageId) {
        String menu = "mibsdk/plugin." + manifest.id();
        String target = pageKey(pageId);
        await(() -> menuLink(menu, target) != null, "a Plugins-menu link to " + target);
        ui.select(menu, menuLink(menu, target));
        await(() -> target.equals(ui.openPageKey()), "the page " + target + " open");
    }

    private String menuLink(String menu, String target) {
        UiPage p = table.snapshot().pages().get(menu);
        if (p != null) {
            for (UiEntry e : p.entries()) {
                if (e.kind() == UiEntry.LINK && target.equals(e.targetPageKey())) {
                    return e.key();
                }
            }
        }
        return null;
    }

    /** Stops the runtime (which flushes the plugin's storage) and reads {@code data/<id>.properties}; empty if none. */
    public Properties storedValues() {
        stopRuntime();
        Properties p = new Properties();
        File f = new File(FakePlatform.roots(tmp).persist(), "data/" + manifest.id() + ".properties");
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + f, e);
            }
        }
        return p;
    }

    /** Stops the runtime and starts a new one on the same temp directory (storage kept); waits until ENABLED. */
    public void restart() {
        stopRuntime();
        begin(true);
    }

    /** The runtime's log, oldest line first. A line starts with the clock reading in milliseconds. */
    public String log() {
        StringBuilder sb = new StringBuilder();
        for (String line : log.snapshot()) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** Stops the runtime. */
    @Override
    public void close() {
        stopRuntime();
    }

    // ---- starting and stopping ----

    private void begin(boolean wantEnabled) {
        car = new FakePlatform("HMI_TEST", features);
        table = new UiTable(64);
        ui = new FakeUi(table);
        log = new RingLog(500);
        SdkRoots roots = FakePlatform.roots(tmp);
        Clock clock = () -> System.nanoTime() / 1000000L + skew.get();
        rt = new SdkRuntime(RuntimeConfig.load(roots), car, clock, new HookTable(64), table, log);
        running = true;
        rt.start();
        awaitSettled(wantEnabled);
    }

    /** Waits for the plugin to settle, then checks that it settled the way the caller wanted. */
    private void awaitSettled(boolean wantEnabled) {
        long deadline = System.nanoTime() + AWAIT_MS * 1000000L;
        while (true) {
            PluginStatus s = status();
            if (s != null && isSettled(s.state())) {
                if ((s.state() == PluginState.ENABLED) != wantEnabled) {
                    throw new AssertionError((wantEnabled ? "plugin did not start: " : "plugin started but was expected to fail: ")
                            + s.state() + "\n" + describe());
                }
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("never " + (wantEnabled ? "ENABLED" : "FAILED or BLOCKED") + "\n" + describe());
            }
            sleep();
        }
    }

    private static boolean isSettled(PluginState st) {
        return st == PluginState.ENABLED || st == PluginState.FAILED || st == PluginState.QUARANTINED
                || st == PluginState.DISABLED || st.name().startsWith("BLOCKED");
    }

    private void stopRuntime() {
        if (running) {
            running = false;
            rt.stop();
        }
    }

    private PluginStatus status() {
        List<PluginStatus> all = rt.statuses();
        return all.size() == 1 ? all.get(0) : null;
    }

    private static void sleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** What a failed wait reports: runtime status, the unit's UI, then the log. */
    private String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("status: ").append(rt.statuses()).append('\n');
        sb.append("car rows: ").append(ui.carRows()).append('\n');
        sb.append("status parts: ").append(ui.statusParts()).append('\n');
        for (String key : new TreeSet<String>(table.snapshot().pages().keySet())) {
            if (key.startsWith(manifest.id() + "/")) {
                sb.append("page ").append(key).append(": ").append(ui.render(key)).append('\n');
            }
        }
        sb.append("dialog: ").append(ui.dialog()).append(", toast: ").append(ui.toast()).append('\n');
        sb.append("log:\n").append(log());
        return sb.toString();
    }

    // ---- the install ----

    private static PluginManifest readManifest(File jar) {
        if (!jar.isFile()) {
            throw new IllegalStateException("The plugin jar " + jar + " does not exist (build it first, e.g. mibPluginJar)");
        }
        try (ZipFile z = new ZipFile(jar)) {
            ZipEntry e = z.getEntry("plugin.json");
            if (e == null) {
                throw new IllegalStateException(jar + " has no plugin.json: not a plugin jar");
            }
            try (InputStream in = z.getInputStream(e)) {
                return ManifestParser.parse(new String(readAll(in), StandardCharsets.UTF_8));
            }
        } catch (IOException | ManifestException e) {
            throw new IllegalStateException("Cannot read plugin.json from " + jar + ": " + e.getMessage(), e);
        }
    }

    private void install(File icons, List<String> grants) {
        try {
            File base = FakePlatform.roots(tmp).install();
            new File(base, "plugins").mkdirs();
            Files.copy(jar.toPath(), new File(base, "plugins/" + manifest.id() + ".jar").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            write(new File(base, "sdk.properties"), "plugin.start.delay.ms=0\nheap.low.bytes=0\nui.publish.min.ms=0\n"
                    + "log.file=" + new File(tmp, "mibsdk.log").getPath() + "\n");
            List<String> granted = grants != null ? grants : manifest.permissions();
            write(new File(base, "grants.properties"), manifest.id() + "=" + String.join(",", granted) + "\n");
            installIcons(new File(base, "icons/" + manifest.id()), icons);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot install the plugin into " + tmp + ": " + e.getMessage(), e);
        }
    }

    /** What mibpkg installs for the package's icons: each PNG as {@code <sha16>.png} and the index lines. */
    private void installIcons(File dir, File source) throws IOException {
        StringBuilder index = new StringBuilder();
        for (String name : manifest.icons()) {
            File png = new File(source, name + ".png");
            if (!png.isFile()) {
                throw new IllegalStateException("plugin.json declares icon '" + name + "' but " + png + " does not exist"
                        + " (set -Dmibsdk.pluginIcons or Builder.icons to the directory with the PNGs)");
            }
            BufferedImage image = ImageIO.read(png);
            if (image == null) {
                throw new IllegalStateException(png + " is not a readable PNG");
            }
            String sha = sha16(Files.readAllBytes(png.toPath()));
            iconShas.put(name, sha);
            dir.mkdirs();
            Files.copy(png.toPath(), new File(dir, sha + ".png").toPath(), StandardCopyOption.REPLACE_EXISTING);
            index.append(name).append('=').append(sha).append(',').append(image.getWidth()).append(',')
                    .append(image.getHeight()).append('\n');
        }
        if (index.length() > 0) {
            write(new File(dir, "index.properties"), index.toString());
        }
    }

    private static String sha16(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xf, 16)).append(Character.forDigit(d[i] & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
