package mibsdk.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
        write(new File(d, "0123456789abcdef.png"), "png");
        write(new File(d, "fedcba9876543210.png"), "png");
        IconIndex i = IconIndex.read("hello", d);
        assertEquals(new File(d, "0123456789abcdef.png").getPath(), i.get("hello").path());
        assertEquals(20, i.get("small").width());
        assertNull(i.get("bad"));
        assertNull(IconIndex.read("hello", new File(d, "missing")).get("hello"));
    }

    @Test
    void anEntryWhosePngIsMissingIsLeftOut(@TempDir File d) throws Exception {
        write(new File(d, "index.properties"), "here=0123456789abcdef,20,20\ngone=fedcba9876543210,20,20\n");
        write(new File(d, "0123456789abcdef.png"), "png");
        new File(d, "fedcba9876543210.png").mkdirs(); // a directory is not the file either
        IconIndex i = IconIndex.read("p", d);
        assertNotNull(i.get("here"));
        assertNull(i.get("gone"));
    }

    @Test
    void entriesCarryOwnerKeyAndSize(@TempDir File d) throws Exception {
        write(new File(d, "index.properties"), "dot=00112233445566ff,20,18\n");
        write(new File(d, "00112233445566ff.png"), "png");
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
        write(new File(d, "0123456789abcdef.png"), "png");
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

    @Test
    void readsReflectionIntoItsBase(@TempDir File d) throws Exception {
        write(new File(d, "index.properties"),
                "hello=0123456789abcdef,72,72\nhello-20=00112233445566ff,20,20\nhello.r=fedcba9876543210,72,68\n");
        write(new File(d, "0123456789abcdef.png"), "png");
        write(new File(d, "00112233445566ff.png"), "png");
        write(new File(d, "fedcba9876543210.png"), "png");
        IconIndex i = IconIndex.read("hello", d);
        UiIcon base = i.get("hello");
        assertEquals("0123456789abcdef", base.key());
        UiIcon r = base.reflection();
        assertNotNull(r);
        assertEquals(UiIcon.FILE, r.kind());
        assertEquals("hello", r.owner());
        assertEquals("fedcba9876543210", r.key());
        assertEquals(new File(d, "fedcba9876543210.png").getPath(), r.path());
        assertEquals(72, r.width());
        assertEquals(68, r.height());
        assertNull(i.get("hello-20").reflection(), "no .r line: no reflection");
        assertNull(i.get("hello.r"), "a .r line is not an icon of its own");
        assertSame(r, base.withOwnColours(true).reflection(), "the colour choice keeps the reflection");

        UiIcon plain = UiIcon.file("hello", base.path(), base.key(), 72, 72);
        assertFalse(plain.equals(base), "the reflection is part of equality");
        assertEquals(plain.withReflection(r), base);
        assertEquals(plain.withReflection(r).hashCode(), base.hashCode());
    }

    @Test
    void reflectionLineWithoutItsFileOrBaseIsIgnored(@TempDir File d) throws Exception {
        write(new File(d, "index.properties"), ""
                + "gone=0123456789abcdef,72,72\n"
                + "gone.r=fedcba9876543210,72,68\n" // its PNG is missing
                + "orphan.r=00112233445566ff,72,68\n" // no base entry
                + "nopng=0011223344556600,72,72\n" // the base's own PNG is missing
                + "nopng.r=00112233445566ff,72,68\n"
                + "bad=0123456789abcdef,72,72\n"
                + "bad.r=00112233445566ff,0,68\n" // malformed value
                + "Bad.r=00112233445566ff,72,68\n" // base not a name
                + ".r=00112233445566ff,72,68\n"
                + "ok=0123456789abcdef,72,72\n"
                + "ok.r=00112233445566ff,72,68\n"); // the control: a whole .r line is read
        write(new File(d, "0123456789abcdef.png"), "png");
        write(new File(d, "00112233445566ff.png"), "png");
        IconIndex i = IconIndex.read("p", d);
        assertNotNull(i.get("gone"));
        assertNull(i.get("gone").reflection());
        assertNull(i.get("orphan"));
        assertNull(i.get("orphan.r"));
        assertNull(i.get("nopng"));
        assertNull(i.get("bad").reflection());
        assertNull(i.get("Bad"));
        assertNull(i.get(""));
        assertNull(i.get(".r"));
        assertEquals("00112233445566ff", i.get("ok").reflection().key());
    }

    /** Regression guard (passes on the pre-reflection reader too): an index with no .r lines reads as before. */
    @Test
    void indexWithoutReflectionsReadsAsBefore(@TempDir File d) throws Exception {
        write(new File(d, "index.properties"), "hello=0123456789abcdef,72,72\nsmall=fedcba9876543210,20,20\n");
        write(new File(d, "0123456789abcdef.png"), "png");
        write(new File(d, "fedcba9876543210.png"), "png");
        IconIndex i = IconIndex.read("hello", d);
        assertEquals(UiIcon.file("hello", new File(d, "0123456789abcdef.png").getPath(), "0123456789abcdef", 72, 72),
                i.get("hello"));
        assertEquals(new File(d, "0123456789abcdef.png").getPath(), i.get("hello").path());
        assertEquals(20, i.get("small").height());
        assertNull(i.get("hello.r"));
    }

    /**
     * Regression guard (passes on the pre-reflection reader too): {@code <name>.r} is never a name, so it is never
     * an icon, and the line leaves its base's key, path and size as they are without it.
     */
    @Test
    void reflectionKeyIsNotAName(@TempDir File d) throws Exception {
        assertFalse(IconIndex.isName("hello.r"));
        File with = new File(d, "with");
        File without = new File(d, "without");
        with.mkdirs();
        without.mkdirs();
        for (File x : new File[] {with, without}) {
            write(new File(x, "0123456789abcdef.png"), "png");
            write(new File(x, "fedcba9876543210.png"), "png");
        }
        write(new File(with, "index.properties"), "hello=0123456789abcdef,72,72\nhello.r=fedcba9876543210,72,68\n");
        write(new File(without, "index.properties"), "hello=0123456789abcdef,72,72\n");
        UiIcon a = IconIndex.read("hello", with).get("hello");
        UiIcon b = IconIndex.read("hello", without).get("hello");
        assertNull(IconIndex.read("hello", with).get("hello.r"));
        assertEquals(b.key(), a.key());
        assertEquals(new File(with, "0123456789abcdef.png").getPath(), a.path());
        assertEquals(new File(without, "0123456789abcdef.png").getPath(), b.path());
        assertEquals(b.width(), a.width());
        assertEquals(b.height(), a.height());
    }
}
