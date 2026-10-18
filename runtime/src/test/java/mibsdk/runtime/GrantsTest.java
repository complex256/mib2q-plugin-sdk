package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.api.Features;
import mibsdk.platform.HookTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrantsTest {

    @TempDir
    File tmp;

    private SdkRuntime rt;

    @AfterEach
    void tearDown() {
        if (rt != null) {
            rt.stop();
        }
    }

    private File write(String name, String text) throws IOException {
        File f = new File(tmp, name);
        f.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        } finally {
            out.close();
        }
        return f;
    }

    private Set<String> set(String... s) {
        return new HashSet<String>(Arrays.asList(s));
    }

    @Test
    void parsesAndTrims() throws Exception {
        File f = write("g.properties", "alpha = vehicle.read , hooks.parking,, \n# comment\nbeta=vehicle.read\nempty=\n");
        Grants g = Grants.load(f);

        assertEquals(set("vehicle.read", "hooks.parking"), g.granted("alpha"));
        assertEquals(set("vehicle.read"), g.granted("beta"));
        assertTrue(g.granted("empty").isEmpty());
        assertTrue(g.granted("unknown").isEmpty());
        assertTrue(g.granted(null).isEmpty());
    }

    @Test
    void corruptFileIsEmpty() throws Exception {
        assertTrue(Grants.load(write("bad.properties", "alpha=vehicle.read\nbroken=\\u12zz\n")).granted("alpha").isEmpty());
        assertTrue(Grants.load(new File(tmp, "missing.properties")).granted("alpha").isEmpty());
        assertTrue(Grants.load(tmp).granted("alpha").isEmpty(), "a directory is not a grants file");
        assertTrue(Grants.load(null).granted("alpha").isEmpty());
    }

    @Test
    void grantedSetIsNotModifiable() throws Exception {
        Grants g = Grants.load(write("g.properties", "alpha=vehicle.read\n"));
        try {
            g.granted("alpha").add("platform.unrestricted");
            org.junit.jupiter.api.Assertions.fail("granted() must be read-only");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    private void start(String grantsText, String pluginId, String[] perms, String[] deps) throws Exception {
        File base = new File(tmp, "mibsdk");
        File plugins = new File(base, "plugins");
        plugins.mkdirs();
        write("mibsdk/sdk.properties", "log.file=" + new File(tmp, "mibsdk.log").getPath() + "\n");
        if (grantsText != null) {
            write("mibsdk/grants.properties", grantsText);
        }
        PluginJars jars = new PluginJars(new File(tmp, "fixtures"));
        jars.write(plugins, pluginId, "1", deps, perms, "ctx.log().info(\"up\");", null);
        FakePlatform fake = new FakePlatform("HMI_TEST", set(Features.PARKING, Features.HOOK_PARKING));
        rt = new SdkRuntime(RuntimeConfig.load(base), fake, new FakeClock(), new HookTable(16), new RingLog(100));
        rt.start();
    }

    private PluginStatus status(String id) {
        List<PluginStatus> all = rt.statuses();
        for (PluginStatus s : all) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        throw new AssertionError("no status for " + id + " in " + all);
    }

    @Test
    void ungrantedPluginBlocked() throws Exception {
        start("needy=vehicle.read\n", "needy", new String[] {"vehicle.read", "hooks.parking", "vehicle.parking.write"}, null);

        assertEquals(PluginState.BLOCKED_PERMISSIONS, status("needy").state());
        String reason = status("needy").reason();
        assertTrue(reason.contains("hooks.parking") && reason.contains("vehicle.parking.write"), reason);
        assertTrue(!reason.contains("vehicle.read"), "granted permissions are not listed as missing: " + reason);
        for (String line : rt.log().snapshot()) {
            assertTrue(!line.endsWith(" needy up"), "a blocked plugin never runs");
        }
    }

    @Test
    void missingGrantsFileBlocksPluginsThatNeedPermissions() throws Exception {
        start(null, "needy", new String[] {"vehicle.read"}, null);

        assertEquals(PluginState.BLOCKED_PERMISSIONS, status("needy").state());
        assertTrue(status("needy").reason().contains("vehicle.read"), status("needy").reason());
    }

    @Test
    void pluginWithoutPermissionsNeedsNoGrants() throws Exception {
        start(null, "modest", new String[0], null);

        assertEquals(PluginState.ENABLED, status("modest").state());
    }
}
