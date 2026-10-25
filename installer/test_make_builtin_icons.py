"""tools/make_builtin_icons.py: its output is deterministic and is exactly what the repo commits (dist/icons/ and the
shared PNG fixtures in build-logic/src/test/resources/png/, which mibpkg's tests copy byte for byte)."""
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
TOOL = os.path.join(REPO, "tools", "make_builtin_icons.py")
ICONS = os.path.join(REPO, "dist", "icons")
FIXTURES = os.path.join(REPO, "build-logic", "src", "test", "resources", "png")
STOCK = ["dot", "circle", "star", "triangle", "square", "check", "cross", "plug"]  # mibsdk.api.StockIcons


def run(*args):
    subprocess.run([sys.executable, "-I", TOOL] + list(args), check=True, stdout=subprocess.DEVNULL)


def listing(d):
    return {n: open(os.path.join(d, n), "rb").read() for n in sorted(os.listdir(d))}


def decode(data):
    """(w, h, rows of (r, g, b, a)) of an 8-bit RGBA, filter-0 PNG as the generator writes it."""
    pos, chunks = 8, {}
    while pos < len(data):
        n, = struct.unpack(">I", data[pos:pos + 4])
        chunks.setdefault(data[pos + 4:pos + 8], b"")
        chunks[data[pos + 4:pos + 8]] += data[pos + 8:pos + 8 + n]
        pos += 12 + n
    w, h, depth, ctype = struct.unpack(">IIBB", chunks[b"IHDR"][:10])
    assert (depth, ctype) == (8, 6)
    raw = zlib.decompress(chunks[b"IDAT"])
    stride = 1 + 4 * w
    rows = []
    for y in range(h):
        line = raw[y * stride:(y + 1) * stride]
        assert line[0] == 0
        rows.append([tuple(line[1 + 4 * x:5 + 4 * x]) for x in range(w)])
    return w, h, rows


class MakeBuiltinIconsTest(unittest.TestCase):
    def test_output_is_byte_identical_to_the_committed_files(self):
        with tempfile.TemporaryDirectory() as t:
            icons, fixtures = os.path.join(t, "icons"), os.path.join(t, "png")
            run("--icons", icons, "--fixtures", fixtures)
            self.assertEqual(listing(ICONS), listing(icons), "dist/icons/ is stale: rerun the generator")
            self.assertEqual(listing(FIXTURES), listing(fixtures), "the PNG fixtures are stale: rerun the generator")

    def test_the_builtin_set_is_three_sizes_of_each_stock_name(self):
        want = sorted(n + s for n in STOCK for s in ("-72.png", "-72r.png", "-20.png"))
        self.assertEqual(want, sorted(os.listdir(ICONS)))

    def test_glyphs_are_white_on_transparent_with_the_status_gap_and_a_faded_reflection(self):
        for n in STOCK:
            w, h, rows = decode(open(os.path.join(ICONS, n + "-72.png"), "rb").read())
            self.assertEqual((72, 72), (w, h))
            self.assertTrue(all(p[:3] == (255, 255, 255) for r in rows for p in r), n)
            self.assertTrue(any(p[3] == 255 for r in rows for p in r), n)
            self.assertTrue(any(p[3] == 0 for r in rows for p in r), n)

            w, h, small = decode(open(os.path.join(ICONS, n + "-20.png"), "rb").read())
            self.assertEqual((20, 20), (w, h))
            self.assertTrue(all(r[18][3] == 0 and r[19][3] == 0 for r in small), n + "-20: right 2-px margin")
            self.assertTrue(any(p[3] > 0 for r in small for p in r[:18]), n)

            w, h, refl = decode(open(os.path.join(ICONS, n + "-72r.png"), "rb").read())
            self.assertEqual((72, 68), (w, h))
            for i in range(68):
                src = rows[71 - i]
                f = 0.4 * (1 - i / 67)
                self.assertEqual([round(p[3] * f) for p in src], [p[3] for p in refl[i]], "%s-72r row %d" % (n, i))

    def test_plugin_sample_writes_the_hello_menu_icons(self):
        with tempfile.TemporaryDirectory() as t:
            run("--plugin-sample", t)
            self.assertEqual(["hello-20.png", "hello.png"], sorted(os.listdir(t)))
            self.assertEqual((72, 72), decode(open(os.path.join(t, "hello.png"), "rb").read())[:2])
            self.assertEqual((20, 20), decode(open(os.path.join(t, "hello-20.png"), "rb").read())[:2])

    def test_named_fixtures_are_the_shared_vectors(self):
        self.assertEqual(sorted([
            "ok-20.png", "ok-72.png", "empty.png", "too-big.png", "truncated.png", "bad-crc.png", "palette.png",
            "rgb.png", "depth16.png", "interlaced.png", "apng.png", "73x20.png", "1x100000.png",
            "ihdr-not-first.png", "iend-not-last.png", "short-idat.png", "compression1.png", "filter1.png",
            "idat-bomb.png", "ihdr-twice.png", "idat-split.png", "row-filter5.png", "idat-trailing.png",
            "zlib-unfinished.png", "iend-trailing-byte.png"]), sorted(os.listdir(FIXTURES)))
        self.assertEqual(32769, os.path.getsize(os.path.join(FIXTURES, "too-big.png")))
        bomb = open(os.path.join(FIXTURES, "idat-bomb.png"), "rb").read()
        self.assertLess(len(bomb), 32768)
        self.assertEqual(1 << 20, len(zlib.decompress(bomb[8 + 25 + 8:-12 - 4])))


def load_tool():
    import importlib.util
    spec = importlib.util.spec_from_file_location("make_builtin_icons", TOOL)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


HELLO_MENU_ICONS = os.path.join(REPO, "plugins", "hello-menu", "src", "main", "icons")


class PluginSampleTest(unittest.TestCase):
    def test_left_white_right_blue(self):
        out = load_tool().plugin_sample()
        for name, size, centre in (("hello.png", 72, 36), ("hello-20.png", 20, 9)):
            w, h, rows = decode(out[name])
            self.assertEqual((size, size), (w, h), name)
            opaque = [(x, p) for r in rows for x, p in enumerate(r) if p[3] > 0]
            self.assertTrue(any(x < centre for x, _ in opaque) and any(x >= centre for x, _ in opaque), name)
            for x, p in opaque:
                self.assertEqual((255, 255, 255) if x < centre else (0, 0, 255), p[:3], "%s x=%d" % (name, x))
            with open(os.path.join(HELLO_MENU_ICONS, name), "rb") as f:
                self.assertEqual(out[name], f.read(), name + " is stale: rerun --plugin-sample")


if __name__ == "__main__":
    unittest.main()
