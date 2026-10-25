package mibsdk.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IconIndexTest {

    private static void write(File f, String s) throws IOException {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(s.getBytes("ISO-8859-1"));
        } finally {
            out.close();
        }
    }

    @Test
    void readsTheIndexAndIgnoresBadLines(@TempDir File d) throws Exception {
        write(new File(d, "index.properties"), "hello=0123456789abcdef,72,72\nbad=xyz,1\nsmall=fedcba9876543210,20,20\n");
        IconIndex i = IconIndex.read("hello", d);
        assertEquals(new File(d, "0123456789abcdef.png").getPath(), i.get("hello").path());
        assertEquals(20, i.get("small").width());
        assertNull(i.get("bad"));
        assertNull(IconIndex.read("hello", new File(d, "missing")).get("hello"));
    }

    @Test
    void entriesCarryOwnerKeyAndSize(@TempDir File d) throws Exception {
        write(new File(d, "index.properties"), "dot=00112233445566ff,20,18\n");
        UiIcon i = IconIndex.read("mibsdk", d).get("dot");
        assertEquals(UiIcon.FILE, i.kind());
        assertEquals("mibsdk", i.owner());
        assertEquals("00112233445566ff", i.key());
        assertEquals(20, i.width());
        assertEquals(18, i.height());
    }

    @Test
    void refusesEveryMalformedEntry(@TempDir File d) throws Exception {
        write(new File(d, "index.properties"), ""
                + "upper=0123456789ABCDEF,20,20\n" // sha16 is lowercase hex
                + "short=0123456789abcde,20,20\n" // 15 characters
                + "long=0123456789abcdef0,20,20\n"
                + "zero=0123456789abcdef,0,20\n"
                + "wide=0123456789abcdef,73,20\n" // over Ui.ROW_ICON_MAX_PX
                + "tall=0123456789abcdef,20,73\n"
                + "neg=0123456789abcdef,-1,20\n"
                + "extra=0123456789abcdef,20,20,20\n"
                + "nonum=0123456789abcdef,a,20\n"
                + "empty=\n"
                + "Bad-Name=0123456789abcdef,20,20\n"
                + "ok=0123456789abcdef,1,1\n");
        IconIndex i = IconIndex.read("p", d);
        for (String n : new String[] {"upper", "short", "long", "zero", "wide", "tall", "neg", "extra", "nonum",
                "empty", "Bad-Name"}) {
            assertNull(i.get(n), n);
        }
        assertNotNull(i.get("ok"));
        assertNull(i.get(null));
    }

    @Test
    void anUnreadableOrMalformedFileIsAnEmptyIndex(@TempDir File d) throws Exception {
        File dir = new File(d, "dir-as-file");
        dir.mkdirs();
        new File(dir, "index.properties").mkdirs(); // a directory where the file should be
        assertNull(IconIndex.read("p", dir).get("x"));
        File bad = new File(d, "bad");
        bad.mkdirs();
        write(new File(bad, "index.properties"), "x=\\uZZZZ\n"); // malformed escape
        assertNull(IconIndex.read("p", bad).get("x"));
        assertNull(IconIndex.read("p", null).get("x"));
    }

    @Test
    void equalityIsKindNameOrOwnerAndKey() {
        assertEquals(UiIcon.stock("dot"), UiIcon.stock("dot"));
        assertEquals(UiIcon.stock("dot").hashCode(), UiIcon.stock("dot").hashCode());
        assertFalse(UiIcon.stock("dot").equals(UiIcon.stock("star")));
        UiIcon a = UiIcon.file("a", "/x/a/k.png", "0123456789abcdef", 20, 20);
        assertEquals(a, UiIcon.file("a", "/y/a/k.png", "0123456789abcdef", 20, 20));
        assertFalse(a.equals(UiIcon.file("b", "/x/b/k.png", "0123456789abcdef", 20, 20)),
                "identical bytes in two packages are two icons");
        assertEquals(UiIcon.STOCK, UiIcon.stock("dot").kind());
        assertEquals("dot", UiIcon.stock("dot").name());
    }
}
