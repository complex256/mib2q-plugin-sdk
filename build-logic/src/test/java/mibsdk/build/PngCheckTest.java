package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class PngCheckTest {

    /** The built-in set (mibsdk.api.StockIcons; build-logic cannot depend on :api). */
    private static final String[] STOCK_ICONS = {"dot", "circle", "star", "triangle", "square", "check", "cross", "plug"};

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = PngCheckTest.class.getResourceAsStream("/png/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return in.readAllBytes();
        }
    }

    @Test
    void refusesEveryMalformedOrOversizedFile() throws Exception {
        assertEquals(20, PngCheck.check(fixture("ok-20.png"), 72).width);
        assertEquals(72, PngCheck.check(fixture("ok-72.png"), 72).height);
        for (String bad : new String[] {"empty.png", "too-big.png", "truncated.png", "bad-crc.png", "palette.png",
                "rgb.png", "depth16.png", "interlaced.png", "apng.png", "73x20.png", "1x100000.png",
                "ihdr-not-first.png", "iend-not-last.png", "short-idat.png", "compression1.png", "filter1.png",
                "idat-bomb.png"}) {
            assertThrows(IllegalArgumentException.class, () -> PngCheck.check(fixture(bad), 72), bad);
        }
        assertTrue(fixture("idat-bomb.png").length < 32768);   // the inflate cap, not the size rule, refuses it
    }

    /** Each shared vector is refused by the rule it was made for, not by an accident earlier in the file. */
    @Test
    void eachFixtureFailsTheRuleItWasMadeFor() throws Exception {
        Map<String, String> rule = new LinkedHashMap<>();
        rule.put("empty.png", "signature");
        rule.put("too-big.png", "32768");
        rule.put("truncated.png", "truncated");
        rule.put("bad-crc.png", "CRC");
        rule.put("palette.png", "colour type 3");
        rule.put("rgb.png", "colour type 2");
        rule.put("depth16.png", "bit depth 16");
        rule.put("interlaced.png", "interlace 1");
        rule.put("apng.png", "acTL");
        rule.put("73x20.png", "73x20");
        rule.put("1x100000.png", "1x100000");
        rule.put("ihdr-not-first.png", "IHDR");
        rule.put("iend-not-last.png", "after IEND");
        rule.put("short-idat.png", "inflates to 1619 bytes, expected 1620");
        rule.put("compression1.png", "compression 1");
        rule.put("filter1.png", "filter 1");
        rule.put("idat-bomb.png", "more than the expected 20808");
        for (Map.Entry<String, String> e : rule.entrySet()) {
            String msg = assertThrows(IllegalArgumentException.class,
                    () -> PngCheck.check(fixture(e.getKey()), 72), e.getKey()).getMessage();
            assertTrue(msg.contains(e.getValue()), e.getKey() + ": " + msg);
        }
        assertEquals(32769, fixture("too-big.png").length);
    }

    @Test
    void reportsColourTypeAndHonoursMaxPx() throws Exception {
        assertEquals(6, PngCheck.check(fixture("ok-20.png"), 72).colorType);
        assertEquals(4, PngCheck.check(fixture("ok-72.png"), 72).colorType);
        assertEquals(20, PngCheck.check(fixture("ok-20.png"), 20).height);
        String msg = assertThrows(IllegalArgumentException.class,
                () -> PngCheck.check(fixture("ok-72.png"), 20)).getMessage();
        assertTrue(msg.contains("72x72"), msg);
    }

    @Test
    void everyBuiltinIconPasses() throws Exception {
        File dir = new File(System.getProperty("mibsdk.repoRoot"), "dist/icons");
        TreeSet<String> want = new TreeSet<>();
        for (String n : STOCK_ICONS) {
            want.addAll(Arrays.asList(n + "-72.png", n + "-72r.png", n + "-20.png"));
        }
        String[] files = dir.list();
        assertNotNull(files, "missing " + dir);
        assertEquals(want, new TreeSet<>(Arrays.asList(files)));
        for (String f : want) {
            String name = f.substring(0, f.length() - ".png".length());
            assertTrue(PluginJson.isIconName(name), f);
            PngCheck.Info info = PngCheck.check(Files.readAllBytes(new File(dir, f).toPath()), 72);
            int w = name.endsWith("-20") ? 20 : 72;
            int h = name.endsWith("-20") ? 20 : name.endsWith("-72r") ? 68 : 72;
            assertEquals(w, info.width, f);
            assertEquals(h, info.height, f);
            assertEquals(6, info.colorType, f);
        }
    }
}
