import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gen_bundles  # noqa: E402

STOCK = os.path.join(HERE, "testdata", "bundles_dsitracer_enabled.properties")
CFG = b"de.audi.tghu.serviceDelegateConfigurator=mibsdk.adapter.hqc.ChainingConfigurator"


def read(path):
    with open(path, "rb") as f:
        return f.read()


class GenBundlesTest(unittest.TestCase):
    def gen(self, src, tracer):
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, "out.properties")
            gen_bundles.main(["generate", "--stock", src, "--tracer-active", tracer, "--out", out])
            return read(out)

    def test_tracer_active(self):
        out = self.gen(STOCK, "true")
        self.assertIn(b"Bundle.Startup.Autostart = DSITracer,MibSdk,FwUtilServices,Profiling,Tracing,ATIPImpl\n", out)
        self.assertIn(CFG + b"\r\n", out)
        self.assertEqual(out.count(b"serviceDelegateConfigurator="), 1)

    def test_tracer_inactive(self):
        out = self.gen(STOCK, "false")
        self.assertIn(b"Bundle.Startup.Autostart = MibSdk,FwUtilServices,Profiling,Tracing,ATIPImpl\n", out)
        self.assertNotIn(b"DSITracer,", out)

    def test_other_lines_unchanged(self):
        stock = read(STOCK).split(b"\n")
        out = self.gen(STOCK, "true").split(b"\n")
        changed = [i for i, l in enumerate(stock[:-1]) if out[i] != l]
        self.assertEqual(len(changed), 2)
        self.assertTrue(out[changed[0]].startswith(b"de.audi.tghu.serviceDelegateConfigurator="))
        self.assertTrue(out[changed[1]].startswith(b"Bundle.Startup.Autostart"))
        extra = out[len(stock) - 1:]
        self.assertEqual(len([l for l in out if l.startswith(b"MibSdk=")]), 1)
        self.assertIn(b"MibSdk=mibsdk.adapter.hqc.SdkBundleActivator", b"\n".join(extra))
        # stock ends "\r\n", so the split leaves a trailing empty element that the appended line replaces
        self.assertEqual(out[len(stock) - 2], stock[-2])

    def test_sdkprops_without_chain(self):
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, "sdk.properties")
            gen_bundles.main(["sdkprops", "--tracer-active", "false", "--out", out])
            self.assertEqual(read(out), b"hook.services=org.dsi.ifc.carparkingsystem.DSICarParkingSystem\n")
            gen_bundles.main(["sdkprops", "--tracer-active", "true", "--out", out])
            self.assertEqual(read(out), (b"chain.configurator=de.audi.tghu.dsitrace.TraceConfigurator\n"
                                         b"hook.services=org.dsi.ifc.carparkingsystem.DSICarParkingSystem\n"))

    def test_idempotent_on_already_generated(self):
        for tracer in ("true", "false"):
            with tempfile.TemporaryDirectory() as d:
                first = os.path.join(d, "a")
                second = os.path.join(d, "b")
                gen_bundles.main(["generate", "--stock", STOCK, "--tracer-active", tracer, "--out", first])
                gen_bundles.main(["generate", "--stock", first, "--tracer-active", tracer, "--out", second])
                self.assertEqual(read(first), read(second))
                out = read(second)
                self.assertEqual(out.count(b"MibSdk=mibsdk"), 1)
                self.assertEqual(out.count(b"MibSdk,"), 1)

    def test_cli_runs(self):
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, "o")
            subprocess.check_call([sys.executable, os.path.join(HERE, "gen_bundles.py"), "generate", "--stock", STOCK,
                                   "--tracer-active", "true", "--out", out])
            self.assertTrue(read(out).endswith(b"MibSdk=mibsdk.adapter.hqc.SdkBundleActivator\r\n"))


if __name__ == "__main__":
    unittest.main()
