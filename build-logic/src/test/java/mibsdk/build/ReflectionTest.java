package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ReflectionTest {

    /** The built-in set (mibsdk.api.StockIcons; build-logic cannot depend on :api). */
    private static final String[] STOCK_ICONS = {"dot", "circle", "star", "triangle", "square", "check", "cross", "plug"};

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = ReflectionTest.class.getResourceAsStream("/png/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return in.readAllBytes();
        }
    }

    private static PngCheck.Pixels solid(int w, int h, int r, int g, int b, int a) {
        byte[] px = new byte[w * h * 4];
        for (int i = 0; i < px.length; i += 4) {
            px[i] = (byte) r;
            px[i + 1] = (byte) g;
            px[i + 2] = (byte) b;
            px[i + 3] = (byte) a;
        }
        return new PngCheck.Pixels(w, h, px);
    }

    private static void assertSamePixels(PngCheck.Pixels want, PngCheck.Pixels got, String what) {
        assertEquals(want.width, got.width, what + " width");
        assertEquals(want.height, got.height, what + " height");
        assertArrayEquals(want.rgba, got.rgba, what + " pixels");
    }

    @Test
    void matchesTheBuiltinReflectionsPixelForPixel() throws Exception {
        File dir = new File(System.getProperty("mibsdk.repoRoot"), "dist/icons");
        for (String g : STOCK_ICONS) {
            PngCheck.Pixels src = PngCheck.rgba(Files.readAllBytes(new File(dir, g + "-72.png").toPath()));
            PngCheck.Pixels want = PngCheck.rgba(Files.readAllBytes(new File(dir, g + "-72r.png").toPath()));
            assertSamePixels(want, PngCheck.rgba(Reflection.encode(Reflection.of(src))), g);
        }
    }

    @Test
    void matchesThePythonVectors() throws Exception {
        for (String v : new String[] {"reflect-rgba-48x45", "reflect-la-72"}) {
            PngCheck.Pixels want = PngCheck.rgba(fixture(v + ".r.png"));
            assertSamePixels(want, Reflection.of(PngCheck.rgba(fixture(v + ".png"))), v);
            assertSamePixels(want, PngCheck.rgba(Reflection.encode(Reflection.of(PngCheck.rgba(fixture(v + ".png"))))),
                    v + " encoded");
        }
        assertEquals(43, PngCheck.rgba(fixture("reflect-rgba-48x45.r.png")).height);
        assertEquals(68, PngCheck.rgba(fixture("reflect-la-72.r.png")).height);
    }

    @Test
    void keepsRgbAndFadesAlpha() {
        PngCheck.Pixels r = Reflection.of(solid(10, 72, 0, 0, 255, 255));
        assertEquals(10, r.width);
        assertEquals(68, r.height);
        for (int y = 0; y < r.height; y++) {
            for (int x = 0; x < r.width; x++) {
                int at = (y * r.width + x) * 4;
                assertEquals(0, r.rgba[at] & 0xff);
                assertEquals(0, r.rgba[at + 1] & 0xff);
                assertEquals(255, r.rgba[at + 2] & 0xff);
            }
        }
        assertEquals(102, r.rgba[3] & 0xff, "row 0 alpha");
        assertEquals(0, r.rgba[r.rgba.length - 1] & 0xff, "last row alpha");
    }

    @Test
    void heightScales() {
        int[][] cases = {{72, 68}, {45, 43}, {40, 38}, {21, 20}};
        for (int[] c : cases) {
            assertEquals(c[1], Reflection.of(solid(3, c[0], 1, 2, 3, 255)).height, c[0] + " rows");
        }
    }

    @Test
    void encodingIsStoredDeterministicAndBounded() {
        byte[] noise = new byte[72 * 72 * 4];
        new Random(42).nextBytes(noise);
        PngCheck.Pixels r = Reflection.of(new PngCheck.Pixels(72, 72, noise));
        byte[] png = Reflection.encode(r);
        assertArrayEquals(png, Reflection.encode(r), "deterministic");
        PngCheck.Info info = PngCheck.check(png, 72);
        assertEquals(72, info.width);
        assertEquals(68, info.height);
        assertEquals(6, info.colorType);
        assertTrue(png.length <= 19720, png.length + " bytes");
        // IDAT data starts after signature (8), IHDR (25) and the IDAT length + type (8): zlib 78 01, then a stored block.
        int at = 8 + 25 + 8;
        assertEquals(0x78, png[at] & 0xff);
        assertEquals(0x01, png[at + 1] & 0xff);
        assertEquals(0, (png[at + 2] >> 1) & 0x03, "BTYPE 00 (stored)");
        assertSamePixels(r, PngCheck.rgba(png), "round trip");
    }
}
