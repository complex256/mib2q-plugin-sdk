#!/usr/bin/env python3
"""Writes coffee-timer's icons: cup.png (72x72, Car-drawer row) and cup-20.png (20x20, status item).

White glyph on a transparent background, 8-bit RGBA, as the SDK's icon rules want. Standard library only.
Usage: python3 make_icons.py <dir>
"""
import os
import struct
import sys
import zlib


def png(w, h, inside):
    rows = b""
    for y in range(h):
        row = b"\x00"  # filter type 0 for every row
        for x in range(w):
            row += b"\xff\xff\xff\xff" if inside(x / w, y / h) else b"\x00\x00\x00\x00"
        rows += row

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)  # 8-bit, RGBA, no interlace
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(rows, 9))
            + chunk(b"IEND", b""))


def cup(u, v):
    """A cup with a handle, in unit coordinates (0..1, y down)."""
    body = 0.15 <= u <= 0.65 and 0.30 <= v <= 0.80
    handle = 0.65 <= u <= 0.85 and 0.40 <= v <= 0.65 and not (0.65 <= u <= 0.75 and 0.47 <= v <= 0.58)
    steam = v <= 0.22 and v >= 0.08 and any(abs(u - c) <= 0.03 for c in (0.28, 0.40, 0.52))
    return body or handle or steam


def cup_small(u, v):
    # The status icon keeps its 2 right-most columns clear: the status text follows the 20-px icon cell directly.
    return u < 0.9 and cup(u / 0.9, v)


out = sys.argv[1] if len(sys.argv) > 1 else "."
os.makedirs(out, exist_ok=True)
for name, w, glyph in (("cup", 72, cup), ("cup-20", 20, cup_small)):
    with open(os.path.join(out, name + ".png"), "wb") as f:
        f.write(png(w, w, glyph))
    print("wrote", os.path.join(out, name + ".png"))
