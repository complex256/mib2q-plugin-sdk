package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckStampTest {
    @TempDir Path tmp;

    private File file(String name, String content) throws Exception {
        Path p = tmp.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.ISO_8859_1));
        return p.toFile();
    }

    @Test
    void sha256MatchesKnownVector() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CheckStamp.sha256(file("abc.bin", "abc")));
    }

    @Test
    void formatIsOrderedAndReproducible() throws Exception {
        File b = file("b.jar", "abc");
        File a = file("a.jar", "abc");
        Map<String, String> attrs = new TreeMap<>();
        attrs.put("plugin.unrestricted", "false");
        attrs.put("plugin.id", "bike-rack");
        List<String> checks = List.of("classVersion48", "linkCheck");
        String h = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        String expected = "format=1\nkind=plugin\nchecks=classVersion48,linkCheck\n"
                + "plugin.id=bike-rack\nplugin.unrestricted=false\n"
                + "sha256.a.jar=" + h + "\nsha256.b.jar=" + h + "\n";
        String first = CheckStamp.format("plugin", checks, attrs, List.of(b, a));
        assertEquals(expected, first);
        assertEquals(first, CheckStamp.format("plugin", checks, attrs, List.of(a, b)));
    }

    @Test
    void unitStampLayoutIsPinned() throws Exception {
        String h = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        File rt = file("mibsdk-runtime.jar", "abc");
        File ad = file("mibsdk-adapter-hqc.jar", "abc");
        File re = file("mibsdk-rt-retrotranslator.jar", "abc");
        File out = tmp.resolve("sub/mibsdk-unit.stamp").toFile();
        CheckStamp.write(out, "unit", List.of("classVersion48", "distLinkCheck"), Map.of(), List.of(rt, ad, re));
        assertEquals("format=1\nkind=unit\nchecks=classVersion48,distLinkCheck\n"
                + "sha256.mibsdk-adapter-hqc.jar=" + h + "\n"
                + "sha256.mibsdk-rt-retrotranslator.jar=" + h + "\n"
                + "sha256.mibsdk-runtime.jar=" + h + "\n",
                new String(Files.readAllBytes(out.toPath()), StandardCharsets.ISO_8859_1));
    }
}
