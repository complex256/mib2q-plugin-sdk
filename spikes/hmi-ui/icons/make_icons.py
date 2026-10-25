#!/usr/bin/env python3
"""Spike C icons: writes 8-bit RGBA PNGs next to this script (pure Python: zlib + struct).

- 609100.png 72x72 white "plug" glyph, 609101.png 72x68 its faded reflection (drawer row 0, I1)
- 609102.png / 609103.png a second, different glyph (star) and its reflection (I3, "icon-next")
- 609110.png, 609111.png 20x20 (popup 609600: filled circle, triangle), 609120.png 20x20 (popup 65: square ring)
- p20.png 20x20 green ring with dot (file-path icon, S2), p30.png 30x30 orange star (provider logo, S5)

Usage: python3 -I spikes/hmi-ui/icons/make_icons.py
"""
import math
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
SS = 4  # supersampling per axis for anti-aliased edges


def png(path, w, h, px):
    """px: list of rows, each a list of (r, g, b, a) tuples."""
    raw = b"".join(b"\x00" + b"".join(struct.pack("4B", *p) for p in row) for row in px)

    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(data)


def render(w, h, inside, rgb=(255, 255, 255)):
    """Coverage-rendered shape: inside(x, y) in unit coords (0..1 of the canvas) -> bool."""
    px = []
    for y in range(h):
        row = []
        for x in range(w):
            hits = 0
            for sy in range(SS):
                for sx in range(SS):
                    if inside((x + (sx + 0.5) / SS) / w, (y + (sy + 0.5) / SS) / h):
                        hits += 1
            row.append(rgb + (round(255 * hits / (SS * SS)),))
        px.append(row)
    return px


def reflection(px, h):
    """Vertically flipped copy of the first h rows from the bottom, alpha fading 50% -> 0."""
    src = px[::-1][:h]
    out = []
    for i, row in enumerate(src):
        f = 0.5 * (1 - i / max(1, h - 1))
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


def star(cx, cy, r_out, r_in, n=5):
    pts = []
    for k in range(2 * n):
        r = r_out if k % 2 == 0 else r_in
        a = -math.pi / 2 + k * math.pi / n
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return polygon(pts)


# A mains plug seen from the front: two prongs on top, a rounded body, a cable going down.
PLUG = union(
    rect(0.33, 0.10, 0.41, 0.32), rect(0.59, 0.10, 0.67, 0.32),         # prongs
    rect(0.22, 0.32, 0.78, 0.58),                                       # body
    polygon([(0.22, 0.58), (0.78, 0.58), (0.62, 0.72), (0.38, 0.72)]),  # taper
    rect(0.46, 0.72, 0.54, 0.92),                                       # cable
)
STAR = star(0.5, 0.53, 0.44, 0.19)


def main():
    out = lambda n: os.path.join(HERE, n)
    plug = render(72, 72, PLUG)
    png(out("609100.png"), 72, 72, plug)
    png(out("609101.png"), 72, 68, reflection(plug, 68))
    st = render(72, 72, STAR)
    png(out("609102.png"), 72, 72, st)
    png(out("609103.png"), 72, 68, reflection(st, 68))
    png(out("609110.png"), 20, 20, render(20, 20, circle(0.5, 0.5, 0.4)))
    png(out("609111.png"), 20, 20, render(20, 20, polygon([(0.5, 0.08), (0.94, 0.9), (0.06, 0.9)])))
    png(out("609120.png"), 20, 20, render(20, 20, lambda x, y: rect(0.1, 0.1, 0.9, 0.9)(x, y)
                                          and not rect(0.3, 0.3, 0.7, 0.7)(x, y)))
    png(out("p20.png"), 20, 20, render(20, 20, union(ring(0.5, 0.5, 0.3, 0.45), circle(0.5, 0.5, 0.15)),
                                       (60, 220, 90)))
    png(out("p30.png"), 30, 30, render(30, 30, STAR, (255, 150, 30)))
    print("icons written to", HERE)


if __name__ == "__main__":
    main()
