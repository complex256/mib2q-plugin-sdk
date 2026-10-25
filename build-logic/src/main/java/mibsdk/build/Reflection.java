package mibsdk.build;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Adler32;
import java.util.zip.CRC32;

/**
 * The faded drawer reflection of a plugin icon, {@code icons/<name>.r.png} (plan "Global Constraints": the recipe of
 * {@code tools/make_builtin_icons.py} {@code reflection}, which makes the built-in {@code -72r}).
 *
 * <p>The image is flipped vertically and cut to {@code H = (h * 68 + 36) / 72} rows, RGB kept, alpha fading 40 % to 0.
 * It is written as 8-bit RGBA, filter 0 on every row, in one zlib stream of stored (uncompressed) deflate blocks, so
 * the bytes do not depend on the JDK's zlib; a 72x72 icon's reflection is at most 19720 bytes.
 */
final class Reflection {
    /** The most bytes one stored deflate block holds. */
    private static final int STORED_MAX = 65535;
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private Reflection() {}

    /** The reflection of src: H rows, row i being source row h - 1 - i with its alpha faded. */
    static PngCheck.Pixels of(PngCheck.Pixels src) {
        int w = src.width;
        int h = src.height;
        int H = (h * 68 + 36) / 72; // the plan's names, so the expressions read as in the recipe
        byte[] out = new byte[w * H * 4];
        for (int i = 0; i < H; i++) {
            System.arraycopy(src.rgba, (h - 1 - i) * w * 4, out, i * w * 4, w * 4);
            for (int x = 0; x < w; x++) {
                int at = (i * w + x) * 4 + 3;
                int a = out[at] & 0xff;
                out[at] = (byte) (int) Math.rint(a * (0.4 * (1.0 - (double) i / (H - 1))));
            }
        }
        return new PngCheck.Pixels(w, H, out);
    }

    /** p as a PNG: RGBA, filter 0, stored deflate; deterministic. */
    static byte[] encode(PngCheck.Pixels p) {
        int stride = 1 + p.width * 4;
        byte[] raw = new byte[p.height * stride];
        for (int y = 0; y < p.height; y++) {
            System.arraycopy(p.rgba, y * p.width * 4, raw, y * stride + 1, p.width * 4); // filter byte 0
        }
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        z.write(0x78);
        z.write(0x01);
        int pos = 0;
        do {
            int n = Math.min(STORED_MAX, raw.length - pos);
            boolean last = pos + n == raw.length;
            z.write(last ? 1 : 0); // BFINAL, BTYPE 00
            z.write(n & 0xff);
            z.write(n >>> 8);
            z.write(~n & 0xff);
            z.write((~n >>> 8) & 0xff);
            z.write(raw, pos, n);
            pos += n;
        } while (pos < raw.length);
        Adler32 adler = new Adler32();
        adler.update(raw, 0, raw.length);
        writeU32(z, adler.getValue());

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(SIGNATURE, 0, SIGNATURE.length);
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeU32(ihdr, p.width);
        writeU32(ihdr, p.height);
        ihdr.write(8); // bit depth
        ihdr.write(6); // RGBA
        ihdr.write(0); // compression
        ihdr.write(0); // filter
        ihdr.write(0); // interlace
        chunk(png, "IHDR", ihdr.toByteArray());
        chunk(png, "IDAT", z.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.ISO_8859_1);
        writeU32(out, data.length);
        out.write(t, 0, t.length);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        writeU32(out, crc.getValue());
    }

    private static void writeU32(ByteArrayOutputStream out, long v) {
        out.write((int) (v >>> 24) & 0xff);
        out.write((int) (v >>> 16) & 0xff);
        out.write((int) (v >>> 8) & 0xff);
        out.write((int) v & 0xff);
    }
}
