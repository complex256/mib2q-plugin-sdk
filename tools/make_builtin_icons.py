#!/usr/bin/env python3
"""The SDK's built-in icon set, the shared PNG test vectors and hello-menu's sample icons (pure Python, deterministic).

For each mibsdk.api.StockIcons name, three white-on-transparent 8-bit RGBA PNGs:
- <name>-72.png   72x72, the Car-drawer row icon;
- <name>-72r.png  72x68, its reflection: the 72x72 glyph flipped and cut to 68 rows, alpha fading 40 % -> 0;
- <name>-20.png   20x20, the status-line icon. Its 2 right-most columns stay transparent: the status layout puts the
                  text straight after the 20-px icon cell, and this margin is the gap.

The deflate streams come from the small encoder below (fixed Huffman codes, greedy matches at a few fixed distances),
not from zlib.compress, whose bytes differ between zlib builds: the output is the same everywhere. Only zlib.crc32 and
zlib.adler32 are used, and those are pure checksums.

Usage (python3 -I tools/make_builtin_icons.py ...):
  (no option)          writes dist/icons/
  --icons DIR          writes the built-in set to DIR instead
  --fixtures DIR       writes the named PNG test vectors (build-logic/src/test/resources/png/; mibpkg copies them)
  --plugin-sample DIR  writes hello-menu's hello.png (72x72) and hello-20.png (20x20)
"""
import math
import os
import struct
import sys
import zlib

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SS = 4  # supersampling per axis for anti-aliased edges
WHITE = (255, 255, 255)

# ---------------------------------------------------------------------------------------------------------- deflate

_LEN_BASE = [3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195,
             227, 258]
_LEN_EXTRA = [0] * 8 + [1] * 4 + [2] * 4 + [3] * 4 + [4] * 4 + [5] * 4 + [0]
_DIST_BASE = [1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073,
              4097, 6145, 8193, 12289, 16385, 24577]
_DIST_EXTRA = [0, 0, 0, 0] + [i // 2 for i in range(2, 28)]


class _Bits:
    def __init__(self):
        self.out, self.acc, self.n = bytearray(), 0, 0

    def put(self, value, count):  # LSB first, as deflate packs everything but Huffman codes
        self.acc |= value << self.n
        self.n += count
        while self.n >= 8:
            self.out.append(self.acc & 0xFF)
            self.acc >>= 8
            self.n -= 8

    def code(self, code, length):  # Huffman codes go in MSB first
        self.put(int(format(code, "0%db" % length)[::-1], 2), length)

    def done(self):
        if self.n:
            self.out.append(self.acc & 0xFF)
        return bytes(self.out)


def _symbol(bits, sym):
    if sym < 144:
        bits.code(0x30 + sym, 8)
    elif sym < 256:
        bits.code(0x190 + sym - 144, 9)
    elif sym < 280:
        bits.code(sym - 256, 7)
    else:
        bits.code(0xC0 + sym - 280, 8)


def _index(table, value):
    i = 0
    while i + 1 < len(table) and table[i + 1] <= value:
        i += 1
    return i


def deflate(raw, distances=(1,)):
    """A zlib stream of raw: one fixed-Huffman block, the longest match (3..258 bytes) at any of distances, else a
    literal. Deterministic by construction."""
    bits = _Bits()
    bits.put(0b011, 3)  # BFINAL=1, BTYPE=01 (fixed Huffman)
    i, n = 0, len(raw)
    while i < n:
        best_len, best_dist = 0, 0
        for d in distances:
            if d > i:
                continue
            k = 0
            while k < 258 and i + k < n and raw[i + k] == raw[i + k - d]:
                k += 1
            if k > best_len:
                best_len, best_dist = k, d
        if best_len >= 3:
            li = _index(_LEN_BASE, best_len)
            _symbol(bits, 257 + li)
            bits.put(best_len - _LEN_BASE[li], _LEN_EXTRA[li])
            di = _index(_DIST_BASE, best_dist)
            bits.code(di, 5)
            bits.put(best_dist - _DIST_BASE[di], _DIST_EXTRA[di])
            i += best_len
        else:
            _symbol(bits, raw[i])
            i += 1
    _symbol(bits, 256)
    return b"\x78\x01" + bits.done() + struct.pack(">I", zlib.adler32(raw) & 0xFFFFFFFF)


# -------------------------------------------------------------------------------------------------------------- PNG

SIGNATURE = b"\x89PNG\r\n\x1a\n"


def chunk(t, d):
    return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)


def ihdr(w, h, depth=8, ctype=6, compression=0, filt=0, interlace=0):
    return chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, depth, ctype, compression, filt, interlace))


def scanlines(px, channels):
    """px: rows of channel tuples; filter type 0 on every row."""
    return b"".join(b"\x00" + b"".join(bytes(p[:channels]) for p in row) for row in px)


def idat(raw, w, channels):
    return chunk(b"IDAT", deflate(raw, (1, channels, 1 + w * channels)))


def png(w, h, px, ctype=6):
    channels = {6: 4, 4: 2}[ctype]
    return SIGNATURE + ihdr(w, h, ctype=ctype) + idat(scanlines(px, channels), w, channels) + chunk(b"IEND", b"")


# ----------------------------------------------------------------------------------------------------------- glyphs

def render(w, h, inside, box=None):
    """White coverage-rendered shape: inside(x, y) in unit coords of box = (x0, y0, size) (default: the whole canvas)."""
    x0, y0, bw, bh = (0, 0, w, h) if box is None else (box[0], box[1], box[2], box[2])
    px = []
    for y in range(h):
        row = []
        for x in range(w):
            hits = 0
            for sy in range(SS):
                for sx in range(SS):
                    if inside((x - x0 + (sx + 0.5) / SS) / bw, (y - y0 + (sy + 0.5) / SS) / bh):
                        hits += 1
            row.append(WHITE + ((255 * hits + SS * SS // 2) // (SS * SS),))
        px.append(row)
    return px


def reflection(px, h):
    """The glyph flipped vertically and cut to its first h rows, alpha fading 40 % -> 0."""
    out = []
    for i, row in enumerate(px[::-1][:h]):
        f = 0.4 * (1 - i / (h - 1))
        out.append([(r, g, b, round(a * f)) for (r, g, b, a) in row])
    return out


def rect(x0, y0, x1, y1):
    return lambda x, y: x0 <= x <= x1 and y0 <= y <= y1


def union(*fs):
    return lambda x, y: any(f(x, y) for f in fs)


def circle(cx, cy, r):
    return lambda x, y: (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def ring(cx, cy, r0, r1):
    return lambda x, y: r0 * r0 <= (x - cx) ** 2 + (y - cy) ** 2 <= r1 * r1


def polygon(pts):
    def inside(x, y):
        c = False
        j = len(pts) - 1
        for i in range(len(pts)):
            xi, yi = pts[i]
            xj, yj = pts[j]
            if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
                c = not c
            j = i
        return c
    return inside


def stroke(ax, ay, bx, by, half):
    """A straight bar of half-width half from (ax, ay) to (bx, by)."""
    dx, dy = bx - ax, by - ay
    k = half / math.hypot(dx, dy)
    nx, ny = -dy * k, dx * k
    return polygon([(ax + nx, ay + ny), (bx + nx, by + ny), (bx - nx, by - ny), (ax - nx, ay - ny)])


def star(cx, cy, r_out, r_in, n=5):
    pts = []
    for k in range(2 * n):
        r = r_out if k % 2 == 0 else r_in
        a = -math.pi / 2 + k * math.pi / n
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return polygon(pts)


GLYPHS = {
    "dot": circle(0.5, 0.5, 0.22),
    "circle": ring(0.5, 0.5, 0.28, 0.42),
    "star": star(0.5, 0.53, 0.44, 0.19),
    "triangle": polygon([(0.5, 0.1), (0.92, 0.86), (0.08, 0.86)]),
    "square": rect(0.16, 0.16, 0.84, 0.84),
    "check": union(stroke(0.14, 0.52, 0.40, 0.78, 0.075), stroke(0.40, 0.78, 0.88, 0.24, 0.075)),
    "cross": union(stroke(0.18, 0.18, 0.82, 0.82, 0.08), stroke(0.82, 0.18, 0.18, 0.82, 0.08)),
    # A mains plug seen from the front: two prongs on top, a body, a taper, a cable going down.
    "plug": union(
        rect(0.33, 0.10, 0.41, 0.32), rect(0.59, 0.10, 0.67, 0.32),
        rect(0.22, 0.32, 0.78, 0.58),
        polygon([(0.22, 0.58), (0.78, 0.58), (0.62, 0.72), (0.38, 0.72)]),
        rect(0.46, 0.72, 0.54, 0.92)),
}

def status(glyph):
    """The 20x20 status glyph: drawn in an 18-px square centred vertically; the 2 right-most columns stay clear."""
    px = render(20, 20, glyph, (0, 1, 18))
    return png(20, 20, [row[:18] + [WHITE + (0,)] * 2 for row in px])


def builtin_icons():
    out = {}
    for name in sorted(GLYPHS):
        big = render(72, 72, GLYPHS[name])
        out[name + "-72.png"] = png(72, 72, big)
        out[name + "-72r.png"] = png(72, 68, reflection(big, 68))
        out[name + "-20.png"] = status(GLYPHS[name])
    return out


def plugin_sample():
    hello = union(ring(0.5, 0.5, 0.30, 0.44), circle(0.5, 0.5, 0.16))
    return {"hello.png": png(72, 72, render(72, 72, hello)),
            "hello-20.png": status(hello)}


# --------------------------------------------------------------------------------------------------------- fixtures

def fixtures():
    """The named vectors: two valid files, then one file per rule, each broken in exactly that rule."""
    ok20 = png(20, 20, render(20, 20, GLYPHS["dot"]))
    grey = [[(255, a) for (_, _, _, a) in row] for row in render(72, 72, GLYPHS["star"])]
    ok72 = png(72, 72, grey, ctype=4)
    head, body = ok20[:8 + 25], ok20[8 + 25:]  # signature + IHDR, then IDAT + IEND
    rgba20 = scanlines(render(20, 20, GLYPHS["dot"]), 4)
    iend = chunk(b"IEND", b"")

    def raw_png(header, data, extra_before=b"", extra_after=b""):
        return SIGNATURE + header + extra_before + chunk(b"IDAT", deflate(data)) + iend + extra_after

    f = {"ok-20.png": ok20, "ok-72.png": ok72, "empty.png": b""}
    # Valid but for its size: an ancillary text chunk pads it to one byte over the 32768 limit.
    pad = 32769 - len(ok20) - 12 - len(b"Comment\x00")
    f["too-big.png"] = head + chunk(b"tEXt", b"Comment\x00" + b"x" * pad) + body
    f["truncated.png"] = ok20[:len(ok20) - 40]  # cut inside the IDAT chunk
    bad = bytearray(ok20)
    bad[8 + 25 + len(body) - 12 - 1] ^= 0x01  # last byte of the IDAT CRC
    f["bad-crc.png"] = bytes(bad)
    f["palette.png"] = SIGNATURE + ihdr(20, 20, ctype=3) + chunk(b"PLTE", b"\xff\xff\xff\x00\x00\x00") \
        + chunk(b"IDAT", deflate(bytes(21 * 20))) + iend
    f["rgb.png"] = raw_png(ihdr(20, 20, ctype=2), bytes(61 * 20))
    f["depth16.png"] = raw_png(ihdr(20, 20, depth=16), bytes(161 * 20))
    f["interlaced.png"] = raw_png(ihdr(20, 20, interlace=1), rgba20)
    f["apng.png"] = head + chunk(b"acTL", struct.pack(">II", 1, 0)) + body
    f["73x20.png"] = raw_png(ihdr(73, 20), bytes((1 + 73 * 4) * 20))
    f["1x100000.png"] = raw_png(ihdr(1, 100000), bytes(5 * 100000))
    f["ihdr-not-first.png"] = SIGNATURE + chunk(b"tEXt", b"Comment\x00first") + ok20[8:]
    f["iend-not-last.png"] = ok20 + chunk(b"tEXt", b"Comment\x00after")
    f["short-idat.png"] = raw_png(ihdr(20, 20), rgba20[:-1])
    f["compression1.png"] = raw_png(ihdr(20, 20, compression=1), rgba20)
    f["filter1.png"] = raw_png(ihdr(20, 20, filt=1), rgba20)
    # A 72x72 RGBA header (20808 bytes expected) whose IDAT inflates to 1 MiB of zeros, in a few KiB of file.
    f["idat-bomb.png"] = raw_png(ihdr(72, 72), bytes(1 << 20))
    # The tightened rules: each file breaks one of them and nothing else.
    f["ihdr-twice.png"] = head + ihdr(20, 20) + body
    stream = deflate(rgba20)
    f["idat-split.png"] = head + chunk(b"IDAT", stream[:40]) + chunk(b"tEXt", b"Comment\x00between") \
        + chunk(b"IDAT", stream[40:]) + iend
    last = len(rgba20) - (1 + 20 * 4)  # the filter byte of row 19, the last row
    f["row-filter5.png"] = raw_png(ihdr(20, 20), rgba20[:last] + b"\x05" + rgba20[last + 1:])
    f["idat-trailing.png"] = head + chunk(b"IDAT", stream + b"\x00") + iend
    # Every byte of the image is there, but the stream lacks its adler32, so it never finishes.
    f["zlib-unfinished.png"] = head + chunk(b"IDAT", stream[:-4]) + iend
    f["iend-trailing-byte.png"] = ok20 + b"\x00"
    return f


# ------------------------------------------------------------------------------------------------------------- main

def write_all(directory, files):
    os.makedirs(directory, exist_ok=True)
    for name in sorted(files):
        with open(os.path.join(directory, name), "wb") as fh:
            fh.write(files[name])
    print("%d files written to %s" % (len(files), directory))


def main(argv):
    opts, i = {}, 0
    while i < len(argv):
        if argv[i] not in ("--icons", "--fixtures", "--plugin-sample") or i + 1 >= len(argv):
            sys.exit("usage: make_builtin_icons.py [--icons DIR] [--fixtures DIR] [--plugin-sample DIR]")
        opts[argv[i]] = argv[i + 1]
        i += 2
    if not opts:
        opts["--icons"] = os.path.join(REPO, "dist", "icons")
    if "--icons" in opts:
        write_all(opts["--icons"], builtin_icons())
    if "--fixtures" in opts:
        write_all(opts["--fixtures"], fixtures())
    if "--plugin-sample" in opts:
        write_all(opts["--plugin-sample"], plugin_sample())


if __name__ == "__main__":
    main(sys.argv[1:])
