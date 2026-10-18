package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeConfigTest {

    @TempDir
    File base;

    @Test
    void defaultsWhenFileMissing() {
        RuntimeConfig c = RuntimeConfig.load(base);
        assertEquals(base, c.baseDir());
        assertEquals(new File(base, "plugins"), c.pluginsDir());
        assertEquals(new File(base, "data"), c.dataDir());
        assertEquals(new File(base, "cache"), c.cacheDir());
        assertEquals(new File("/tmp/mibsdk.log"), c.logFile());
        assertEquals(30000, c.pluginStartDelayMs());
        assertEquals(1000, c.tickMs());
        assertEquals(5000, c.slowMs());
        assertEquals(30000, c.hungMs());
        assertEquals(3, c.quarantineFailures());
        assertEquals(60000, c.quarantineWindowMs());
        assertEquals(3, c.safeModeAfterUnhealthyBoots());
        assertEquals(60000, c.healthyAfterMs());
        assertNull(c.chainConfigurator());
        assertEquals(Collections.singletonList("org.dsi.ifc.carparkingsystem.DSICarParkingSystem"), c.hookServices());
        assertEquals(2000, c.devPollMs());
        assertEquals(8388608, c.heapLowBytes());
        assertEquals(3, c.heapLowChecks());
        assertEquals(5000, c.heapCheckMs());
    }

    @Test
    void readsOverridesAndHookServicesList() throws Exception {
        Files.write(new File(base, "sdk.properties").toPath(), (""
                + "plugin.start.delay.ms=0\n"
                + "callback.hung.ms = 1234 \n"
                + "quarantine.failures=5\n"
                + "chain.configurator=de.audi.tghu.dsitrace.TraceConfigurator\n"
                + "hook.services=a, b,\n"
                + "heap.low.bytes=not-a-number\n"
                + "tick.ms=-5\n"
                + "log.file=/tmp/other.log\n").getBytes(StandardCharsets.ISO_8859_1));
        RuntimeConfig c = RuntimeConfig.load(base);
        assertEquals(0, c.pluginStartDelayMs());
        assertEquals(1234, c.hungMs());
        assertEquals(5, c.quarantineFailures());
        assertEquals("de.audi.tghu.dsitrace.TraceConfigurator", c.chainConfigurator());
        assertEquals(Arrays.asList("a", "b"), c.hookServices());
        assertEquals(8388608, c.heapLowBytes(), "unparsable value falls back to the default");
        assertEquals(1000, c.tickMs(), "out-of-range value falls back to the default");
        assertEquals(new File("/tmp/other.log"), c.logFile());
        assertEquals(5000, c.slowMs(), "absent key keeps its default");
    }
}
