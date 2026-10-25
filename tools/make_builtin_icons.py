#!/usr/bin/env python3
"""The SDK's built-in icon set, the shared PNG test vectors and the sample plugins' icons (pure Python, deterministic).

For each mibsdk.api.StockIcons name, three white-on-transparent 8-bit RGBA PNGs:
- <name>-72.png   72x72, the Car-drawer row icon;
- <name>-72r.png  72x68, its reflection: the 72x72 glyph flipped and cut to 68 rows, alpha fading 40 % -> 0;
- <name>-20.png   20x20, the status-line icon. Its 2 right-most columns stay transparent: the status layout puts the
                  text straight after the 20-px icon cell, and this margin is the gap.

The deflate streams come from the small encoder below (fixed Huffman codes, greedy matches at a few fixed distances),
not from zlib.compress, whose bytes differ between zlib builds: the output is the same everywhere. Only zlib.crc32 and
zlib.adler32 are used, and those are pure checksums.

The geometry is platform-independent too: it uses only + - * / and comparisons on float literals, which IEEE 754
rounds the same everywhere. math.cos/sin/hypot and ** are not guaranteed bit-exact across platform maths libraries,
so the star's vertices and the stroke lengths are precomputed literals (repr, so they round-trip exactly); math is used
only to check those literals, never to compute a pixel.

Usage (python3 -I tools/make_builtin_icons.py ...):
  (no option)          writes dist/icons/
  --icons DIR          writes the built-in set to DIR instead
  --fixtures DIR       writes the named PNG test vectors (build-logic/src/test/resources/png/; mibpkg copies them)
  --plugin-sample DIR  writes hello-menu's hello.png (72x72) and hello-20.png (20x20): half white, half blue
  --plugin-bike-rack DIR  writes bike-rack's bike.png (72x72) and bike-20.png (20x20): a white bicycle
"""
import math
import os
import struct
import sys
import zlib

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SS = 4  # supersampling per axis for anti-aliased edges
WHITE = (255, 255, 255)
BLUE = (0, 0, 255)

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


def reflect_height(h):
    """The reflection's row count for an h-row icon, in integers (72 -> 68, 45 -> 43, 40 -> 38, 21 -> 20)."""
    return (h * 68 + 36) // 72


def reflection(px, h):
    """The RGBA image flipped vertically and cut to its first h rows (h = reflect_height of its height), alpha fading
    40 % -> 0. build-logic's Reflection is the same recipe: (int) Math.rint(a * (0.4 * (1.0 - (double) i / (H - 1))))."""
    out = []
    for i, row in enumerate(px[::-1][:h]):
        f = 0.4 * (1 - i / (h - 1))
        out.append([(r, g, b, round(a * f)) for (r, g, b, a) in row])
    return out


def rect(x0, y0, x1, y1):
    return lambda x, y: x0 <= x <= x1 and y0 <= y <= y1


def union(*fs):
    return lambda x, y: any(f(x, y) for f in fs)


def _d2(x, y, cx, cy):
    dx, dy = x - cx, y - cy
    return dx * dx + dy * dy


def circle(cx, cy, r):
    return lambda x, y: _d2(x, y, cx, cy) <= r * r


def ring(cx, cy, r0, r1):
    return lambda x, y: r0 * r0 <= _d2(x, y, cx, cy) <= r1 * r1


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


def _literal(value, exact):
    """value, a precomputed literal, after checking it against exact (computed with math: only the check uses it)."""
    if abs(value - exact) > 1e-12:
        raise AssertionError("literal %r is not %r" % (value, exact))
    return value


def stroke(ax, ay, bx, by, half, length):
    """A straight bar of half-width half from (ax, ay) to (bx, by); length is |b - a|, a literal."""
    dx, dy = bx - ax, by - ay
    k = half / _literal(length, math.hypot(dx, dy))
    nx, ny = -dy * k, dx * k
    return polygon([(ax + nx, ay + ny), (bx + nx, by + ny), (bx - nx, by - ny), (ax - nx, ay - ny)])


def star(cx, cy, r_out, r_in, pts, n=5):
    """A star of n points; pts are its 2n vertices as literals, outer first, clockwise from the top."""
    for k, (x, y) in enumerate(pts):
        r = r_out if k % 2 == 0 else r_in
        a = -math.pi / 2 + k * math.pi / n
        _literal(x, cx + r * math.cos(a))
        _literal(y, cy + r * math.sin(a))
    return polygon(pts)


STAR_PTS = [(0.5, 0.09000000000000002), (0.6116791979355699, 0.37628677106876),
            (0.9184648671698676, 0.3940325224750232), (0.6807007380960792, 0.58871322893124),
            (0.7586255110086881, 0.8859674775249768), (0.5, 0.72),
            (0.24137448899131186, 0.8859674775249768), (0.31929926190392083, 0.58871322893124),
            (0.08153513283013242, 0.3940325224750232), (0.3883208020644301, 0.37628677106876)]


GLYPHS = {
    "dot": circle(0.5, 0.5, 0.22),
    "circle": ring(0.5, 0.5, 0.28, 0.42),
    "star": star(0.5, 0.53, 0.44, 0.19, STAR_PTS),
    "triangle": polygon([(0.5, 0.1), (0.92, 0.86), (0.08, 0.86)]),
    "square": rect(0.16, 0.16, 0.84, 0.84),
    "check": union(stroke(0.14, 0.52, 0.40, 0.78, 0.075, 0.36769552621700474),
                   stroke(0.40, 0.78, 0.88, 0.24, 0.075, 0.7224956747275377)),
    "cross": union(stroke(0.18, 0.18, 0.82, 0.82, 0.08, 0.9050966799187807),
                   stroke(0.82, 0.18, 0.18, 0.82, 0.08, 0.9050966799187807)),
    # A mains plug seen from the front: two prongs on top, a body, a taper, a cable going down.
    "plug": union(
        rect(0.33, 0.10, 0.41, 0.32), rect(0.59, 0.10, 0.67, 0.32),
        rect(0.22, 0.32, 0.78, 0.58),
        polygon([(0.22, 0.58), (0.78, 0.58), (0.62, 0.72), (0.38, 0.72)]),
        rect(0.46, 0.72, 0.54, 0.92)),
}

def status(glyph, paint=lambda px: px):
    """The 20x20 status glyph: drawn in an 18-px square centred vertically; the 2 right-most columns stay clear.
    paint recolours the 20x20 render before the margin is cleared."""
    px = paint(render(20, 20, glyph, (0, 1, 18)))
    return png(20, 20, [row[:18] + [WHITE + (0,)] * 2 for row in px])


def blue_from(split):
    """Paints every pixel at x >= split blue, keeping its alpha; the rest stays white."""
    return lambda px: [[(BLUE if x >= split else p[:3]) + (p[3],) for x, p in enumerate(row)] for row in px]


def builtin_icons():
    out = {}
    for name in sorted(GLYPHS):
        big = render(72, 72, GLYPHS[name])
        out[name + "-72.png"] = png(72, 72, big)
        out[name + "-72r.png"] = png(72, reflect_height(72), reflection(big, reflect_height(72)))
        out[name + "-20.png"] = status(GLYPHS[name])
    return out


def plugin_sample():
    """hello-menu's ring-and-dot, white left of the glyph box's centre and blue right of it, so own colours show."""
    hello = union(ring(0.5, 0.5, 0.30, 0.44), circle(0.5, 0.5, 0.16))
    return {"hello.png": png(72, 72, blue_from(36)(render(72, 72, hello))),
            "hello-20.png": status(hello, blue_from(9))}  # the 18-px box's centre


# A bicycle seen from the side: two wheels, the diamond frame (chain stay, seat tube, seat stay, top tube, down tube),
# the fork, a saddle and a handlebar. The frame joints: rear hub, bottom bracket, seat cluster, head, front hub.
_REAR, _BB, _SEAT, _HEAD, _FRONT = (0.24, 0.60), (0.47, 0.60), (0.39, 0.32), (0.68, 0.32), (0.76, 0.60)
_TUBE = 0.035  # half the tube width
BIKE = union(
    ring(_REAR[0], _REAR[1], 0.155, 0.215), ring(_FRONT[0], _FRONT[1], 0.155, 0.215),
    stroke(_REAR[0], _REAR[1], _BB[0], _BB[1], _TUBE, 0.22999999999999998),
    stroke(_BB[0], _BB[1], _SEAT[0], _SEAT[1], _TUBE, 0.29120439557122074),
    stroke(_SEAT[0], _SEAT[1], _REAR[0], _REAR[1], _TUBE, 0.31764760348537185),
    stroke(_SEAT[0], _SEAT[1], _HEAD[0], _HEAD[1], _TUBE, 0.29000000000000004),
    stroke(_BB[0], _BB[1], _HEAD[0], _HEAD[1], _TUBE, 0.3500000000000001),
    stroke(0.66, 0.24, _FRONT[0], _FRONT[1], _TUBE, 0.37363083384538814),  # fork, from the stem
    stroke(_SEAT[0], _SEAT[1], 0.37, 0.25, _TUBE, 0.0728010988928052),  # seat post
    rect(0.29, 0.21, 0.45, 0.25),  # saddle
    rect(0.60, 0.20, 0.72, 0.24))  # handlebar


def plugin_bike_rack():
    """bike-rack's white bicycle: bike.png for its Car row, bike-20.png for its RACK status item (both tinted)."""
    return {"bike.png": png(72, 72, render(72, 72, BIKE)), "bike-20.png": status(BIKE)}


# --------------------------------------------------------------------------------------------------------- fixtures

def _paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    return a if pa <= pb and pa <= pc else b if pb <= pc else c


def filtered(rows, types, bpp=4):
    """rows: raw scanlines (bytes, no filter byte); types: the filter type (0..4) of each row. Returns the filtered
    scanlines, each led by its filter byte."""
    out, prev = bytearray(), bytes(len(rows[0]))
    for row, t in zip(rows, types):
        line = bytearray()
        for x, v in enumerate(row):
            a = row[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            pred = (0, a, b, (a + b) // 2, _paeth(a, b, c))[t]
            line.append((v - pred) & 0xFF)
        out += bytes([t]) + line
        prev = row
    return bytes(out)


def reflect_vectors():
    """Sources and their reflections (build-logic's Reflection must match them pixel for pixel): a coloured RGBA
    48x45 glyph, and a grey + alpha 72x72 one (expanded to (g, g, g, a) before reflecting)."""
    tri = render(48, 45, GLYPHS["triangle"])
    rgba = [[((x * 5) % 256, (y * 5) % 256, (x * y) % 256, p[3]) for x, p in enumerate(row)]
            for y, row in enumerate(tri)]
    la = [[((64 + 2 * x) % 256, p[3]) for x, p in enumerate(row)] for row in render(72, 72, GLYPHS["plug"])]
    la_rgba = [[(g, g, g, a) for (g, a) in row] for row in la]
    return {"reflect-rgba-48x45.png": png(48, 45, rgba),
            "reflect-rgba-48x45.r.png": png(48, reflect_height(45), reflection(rgba, reflect_height(45))),
            "reflect-la-72.png": png(72, 72, la, ctype=4),
            "reflect-la-72.r.png": png(72, reflect_height(72), reflection(la_rgba, reflect_height(72)))}


def filter_vector():
    """A 12x10 RGBA image whose rows use every filter type (row 0 is Paeth over the all-zero row before it), and its
    raw pixels."""
    w, h, seed, rows = 12, 10, 7, []
    for _ in range(h):
        row = bytearray()
        for _ in range(w * 4):
            seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
            row.append(seed >> 16 & 0xFF)
        rows.append(bytes(row))
    types = [(y * 3 + 4) % 5 for y in range(h)]
    data = SIGNATURE + ihdr(w, h) + chunk(b"IDAT", deflate(filtered(rows, types))) + chunk(b"IEND", b"")
    return {"filters-1234.png": data, "filters-1234.rgba": b"".join(rows)}


def fixtures():
    """The named vectors: two valid files, then one file per rule, each broken in exactly that rule; then the
    reflection vectors and the filter-type vector (valid files, compared by decoded pixels)."""
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
    f.update(reflect_vectors())
    f.update(filter_vector())
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
        if argv[i] not in ("--icons", "--fixtures", "--plugin-sample", "--plugin-bike-rack") or i + 1 >= len(argv):
            sys.exit("usage: make_builtin_icons.py [--icons DIR] [--fixtures DIR] [--plugin-sample DIR]"
                     " [--plugin-bike-rack DIR]")
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
    if "--plugin-bike-rack" in opts:
        write_all(opts["--plugin-bike-rack"], plugin_bike_rack())


if __name__ == "__main__":
    main(sys.argv[1:])
