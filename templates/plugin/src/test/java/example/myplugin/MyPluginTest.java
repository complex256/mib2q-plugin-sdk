package example.myplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import mibsdk.test.PluginTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the built plugin jar on the real SDK runtime and a fake car. The build wires the jar and the icons in. */
class MyPluginTest {

    @Test
    void startsEnabled(@TempDir File tmp) {
        try (PluginTestHarness h = PluginTestHarness.start(tmp)) {
            assertEquals("ENABLED", h.state());
        }
    }
}
