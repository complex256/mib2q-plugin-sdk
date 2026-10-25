package mibsdk.build;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The icon PNG rules (plan "Icons"), checked at plugin build; mibpkg's install-time {@code png_check} applies the
 * same rules to the same shared vectors ({@code src/test/resources/png/}).
 *
 * <ul>
 *   <li>at most {@link #MAX_BYTES} bytes, the PNG signature;</li>
 *   <li>IHDR first (13 bytes): bit depth 8, colour type 6 (RGBA) or 4 (grey + alpha), compression 0, filter 0,
 *       interlace 0, width and height 1..maxPx;</li>
 *   <li>every chunk complete with a correct CRC, one IHDR, no {@code acTL}/{@code fcTL}/{@code fdAT}, the IDAT
 *       chunks consecutive, IEND last (nothing, not even a byte, after it);</li>
 *   <li>the concatenated IDAT data is one complete zlib stream that inflates to exactly
 *       {@code height * (1 + width * channels)} bytes, with no data after the stream's end and every row's filter
 *       byte 0..4. Inflation stops at expected + 1 bytes, so a small file
 *       that inflates to megabytes costs no more than a valid one.</li>
 * </ul>
 */
public final class PngCheck {
    static final int MAX_BYTES = 32768;
    /** The largest icon (mirrors mibsdk.api.Ui.ROW_ICON_MAX_PX); status icons are further held to 20 at runtime. */
    static final int MAX_PX = 72;
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    static final class Info {
        final int width, height, colorType;

        Info(int width, int height, int colorType) {
            this.width = width;
            this.height = height;
            this.colorType = colorType;
        }
    }

    private PngCheck() {}

    /** Checks png against the icon rules with width and height at most maxPx; throws naming the rule that failed. */
    static Info check(byte[] png, int maxPx) {
        if (png.length > MAX_BYTES) {
            throw refuse("file is " + png.length + " bytes, more than " + MAX_BYTES);
        }
        if (png.length < SIGNATURE.length) {
            throw refuse("no PNG signature");
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (png[i] != SIGNATURE[i]) {
                throw refuse("no PNG signature");
            }
        }
        Info info = null;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        boolean iend = false;
        int idatRun = 0; // 0: no IDAT yet, 1: in the IDAT run, 2: the run has ended
        int pos = SIGNATURE.length;
        while (pos < png.length) {
            if (iend) {
                throw refuse("data after IEND");
            }
            if (png.length - pos < 12) {
                throw refuse("truncated chunk at offset " + pos);
            }
            long len = u32(png, pos);
            String type = new String(png, pos + 4, 4, StandardCharsets.ISO_8859_1);
            if (len > png.length - pos - 12) {
                throw refuse("truncated " + type + " chunk at offset " + pos);
            }
            int n = (int) len;
            CRC32 crc = new CRC32();
            crc.update(png, pos + 4, 4 + n);
            if (crc.getValue() != u32(png, pos + 8 + n)) {
                throw refuse("bad CRC in " + type + " chunk at offset " + pos);
            }
            if (idatRun == 1 && !type.equals("IDAT")) {
                idatRun = 2;
            }
            if (info == null) {
                if (!type.equals("IHDR")) {
                    throw refuse("first chunk is " + type + ", not IHDR");
                }
                info = header(png, pos + 8, n, maxPx);
            } else if (type.equals("IHDR")) {
                throw refuse("second IHDR chunk at offset " + pos);
            } else if (type.equals("acTL") || type.equals("fcTL") || type.equals("fdAT")) {
                throw refuse("animated PNG (" + type + " chunk)");
            } else if (type.equals("IDAT")) {
                if (idatRun == 2) {
                    throw refuse("IDAT chunks are not consecutive (another IDAT at offset " + pos + ")");
                }
                idatRun = 1;
                idat.write(png, pos + 8, n);
            } else if (type.equals("IEND")) {
                iend = true;
            }
            pos += 12 + n;
        }
        if (!iend) {
            throw refuse("no IEND chunk");
        }
        int channels = info.colorType == 6 ? 4 : 2;
        inflateExactly(idat.toByteArray(), info.height, 1 + info.width * channels);
        return info;
    }

    private static Info header(byte[] png, int at, int len, int maxPx) {
        if (len != 13) {
            throw refuse("IHDR is " + len + " bytes, not 13");
        }
        long w = u32(png, at);
        long h = u32(png, at + 4);
        int depth = png[at + 8] & 0xff;
        int colorType = png[at + 9] & 0xff;
        int compression = png[at + 10] & 0xff;
        int filter = png[at + 11] & 0xff;
        int interlace = png[at + 12] & 0xff;
        if (depth != 8) {
            throw refuse("bit depth " + depth + ", must be 8");
        }
        if (colorType != 6 && colorType != 4) {
            throw refuse("colour type " + colorType + ", must be 6 (RGBA) or 4 (grey + alpha)");
        }
        if (compression != 0) {
            throw refuse("compression " + compression + ", must be 0");
        }
        if (filter != 0) {
            throw refuse("filter " + filter + ", must be 0");
        }
        if (interlace != 0) {
            throw refuse("interlace " + interlace + ", must be 0");
        }
        if (w < 1 || h < 1 || w > maxPx || h > maxPx) {
            throw refuse("size " + w + "x" + h + ", width and height must be 1.." + maxPx);
        }
        return new Info((int) w, (int) h, colorType);
    }

    /** IDAT is one complete zlib stream, nothing after it, of exactly height rows of stride bytes, each filter 0..4. */
    private static void inflateExactly(byte[] idat, int height, int stride) {
        int expected = height * stride;
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(idat);
            byte[] out = new byte[expected + 1];
            int got = 0;
            while (got < out.length && !inflater.finished()) {
                int k = inflater.inflate(out, got, out.length - got);
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                got += k;
            }
            if (got > expected) {
                throw refuse("IDAT inflates to more than the expected " + expected + " bytes");
            }
            if (!inflater.finished()) {
                throw refuse("IDAT is not a complete zlib stream (" + got + " of " + expected + " bytes)");
            }
            if (got != expected) {
                throw refuse("IDAT inflates to " + got + " bytes, expected " + expected);
            }
            if (inflater.getRemaining() > 0) {
                throw refuse("IDAT has " + inflater.getRemaining() + " bytes of data after the zlib stream end");
            }
            for (int y = 0; y < height; y++) {
                int filter = out[y * stride] & 0xff;
                if (filter > 4) {
                    throw refuse("row " + y + " has filter type " + filter + ", must be 0..4");
                }
            }
        } catch (DataFormatException e) {
            throw refuse("IDAT is not valid zlib data: " + e.getMessage());
        } finally {
            inflater.end();
        }
    }

    private static long u32(byte[] b, int at) {
        return ((long) (b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
    }

    private static IllegalArgumentException refuse(String rule) {
        return new IllegalArgumentException("icon PNG refused: " + rule);
    }
}
