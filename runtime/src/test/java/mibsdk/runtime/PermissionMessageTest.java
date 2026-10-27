package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.api.Features;
import mibsdk.api.PermissionDeniedException;
import mibsdk.platform.HookTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A denied permission says whether the manifest never declared it or the owner never granted it. */
class PermissionMessageTest {

    @TempDir
    File tmp;

    private File base;
    private File plugins;
    private PluginJars jars;
    private FakePlatform fake;
    private SdkRuntime rt;
    private final StringBuilder grants = new StringBuilder();

    @BeforeEach
    void setUp() {
        base = new File(tmp, "mibsdk");
        plugins = new File(base, "plugins");
        plugins.mkdirs();
        jars = new PluginJars(new File(tmp, "fixtures"));
        SerialExecutor.watchdogPeriodMs = 10;
        fake = new FakePlatform("HMI_TEST", new HashSet<String>(Arrays.asList(Features.IGNITION)));
    }

    @AfterEach
    void tearDown() {
        if (rt != null) {
            rt.stop();
        }
        SerialExecutor.watchdogPeriodMs = 1000;
    }

    private static final String TRY_NET = ""
            + "try { ctx.net(); ctx.log().info(\"net allowed\"); }\n"
            + "catch (PermissionDeniedException e) {\n"
            + "  ctx.log().info(\"net \" + e.getClass().getSimpleName() + \" [\" + e.getMessage() + \"] \" + e.permission());\n"
            + "}";

    private void plugin(String id, String[] perms, String[] granted) throws IOException {
        jars.write(plugins, id, "1", null, perms, TRY_NET, null);
        grants.append(id).append('=');
        for (int i = 0; i < granted.length; i++) {
            grants.append(i > 0 ? "," : "").append(granted[i]);
        }
        grants.append('\n');
    }

    private void start() throws IOException {
        write("sdk.properties", "plugin.start.delay.ms=0\nheap.low.bytes=0\nlog.file="
                + new File(tmp, "mibsdk.log").getPath() + "\n");
        write("grants.properties", grants.toString());
        rt = new SdkRuntime(RuntimeConfig.load(FakePlatform.roots(tmp)), fake, new FakeClock(), new HookTable(64),
                new RingLog(500));
        rt.start();
    }

    private void write(String name, String text) throws IOException {
        FileOutputStream out = new FileOutputStream(new File(base, name));
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private void awaitLine(String source, String msg) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            for (String line : rt.log().snapshot()) {
                if (line.endsWith(" " + source + " " + msg)) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        fail("expected '" + source + " " + msg + "' in\n" + rt.log().snapshot());
    }

    @Test
    void undeclaredSaysNotDeclared() throws Exception {
        // The owner granted network, but the manifest never asked for it.
        plugin("silent", new String[0], new String[] {"network"});
        start();
        awaitLine("silent", "net UndeclaredPermissionException [permission not declared in plugin.json: network] network");
    }

    @Test
    void declaredButNotGrantedSaysNotGranted() {
        // SdkRuntime never enables a plugin whose declared permission is ungranted (BLOCKED_PERMISSIONS), so build
        // the context directly: network is declared but not effective, vehicle.read is neither.
        Set<String> declared = new HashSet<String>(Arrays.asList("network"));
        PluginContextImpl ctx = new PluginContextImpl("p", null, null, null, null, null, new Registrations(),
                new LiveFeatures(new HashSet<String>()), new HashSet<String>(), declared, null, null, null, null,
                null, Collections.<String>emptyList(), null);

        PermissionDeniedException net = assertThrows(PermissionDeniedException.class, ctx::net);
        assertEquals(PermissionDeniedException.class, net.getClass());
        assertEquals("permission not granted: network", net.getMessage());
        assertEquals("network", net.permission());

        PermissionDeniedException vehicle = assertThrows(PermissionDeniedException.class, ctx::vehicle);
        assertEquals(UndeclaredPermissionException.class, vehicle.getClass());
        assertEquals("permission not declared in plugin.json: vehicle.read", vehicle.getMessage());
        assertEquals("vehicle.read", vehicle.permission());

        PermissionDeniedException ui = assertThrows(PermissionDeniedException.class,
                () -> ctx.ui().statusItem("x"));
        assertEquals("permission not declared in plugin.json: ui.slot.statusbar", ui.getMessage());
    }
}
