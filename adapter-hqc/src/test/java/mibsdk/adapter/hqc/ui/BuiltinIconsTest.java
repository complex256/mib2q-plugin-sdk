package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import mibsdk.platform.UiIcon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuiltinIconsTest {

    @TempDir
    File dir;

    private void png(String key) throws IOException {
        Files.write(new File(dir, key + ".png").toPath(), new byte[] {1});
    }

    @Test
    void readsTheSdkIndex() throws IOException {
        png("aaaa000000000000");
        png("bbbb000000000000");
        png("cccc000000000000");
        Files.write(new File(dir, "index.properties").toPath(), ("check-20=cccc000000000000,20,20\n"
                + "check-72=aaaa000000000000,72,72\ncheck-72r=bbbb000000000000,72,68\n"
                + "star-72=dddd000000000000,72,72\n").getBytes(StandardCharsets.ISO_8859_1));
        BuiltinIcons b = BuiltinIcons.read(dir);

        UiIcon icon = b.get("check", 72, false);
        assertEquals(UiIcon.FILE, icon.kind());
        assertEquals("mibsdk", icon.owner());
        assertEquals("aaaa000000000000", icon.key());
        assertEquals(new File(dir, "aaaa000000000000.png").getPath(), icon.path());
        assertEquals("bbbb000000000000", b.get("check", 72, true).key());
        assertEquals(68, b.get("check", 72, true).height());
        assertEquals("cccc000000000000", b.get("check", 20, false).key());
        assertNull(b.get("star", 72, false), "its PNG is not installed");
        assertNull(b.get("check", 20, true));
        assertNull(b.get(null, 72, false));
        assertNull(BuiltinIcons.read(new File(dir, "missing")).get("check", 72, false));
        assertNull(BuiltinIcons.read(null).get("check", 72, false));
        assertNull(BuiltinIcons.NONE.get("check", 72, false));
    }
}
