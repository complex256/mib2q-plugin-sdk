"""Drives installer/mibsdk-dev against a fake unit: a directory tree standing in for the head unit's file system,
reached through a fake `ssh` on PATH that runs each remote command locally with the unit's absolute paths
rewritten into that tree. Nothing ever touches a real device."""
import os
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "mibsdk-dev")
STOCK = os.path.join(HERE, "testdata", "bundles_dsitracer_enabled.properties")
HMI = "HMI_AU_MIB2_HQC_NAR_G22_C10_180803"
SDK_JARS = ["mibsdk-runtime.jar", "mibsdk-adapter-hqc.jar", "mibsdk-rt-retrotranslator.jar"]

FAKE_SSH = r"""#!/usr/bin/env bash
# Fake ssh for the fake unit: drops the options and the user@host, runs the command locally under $FAKE_UNIT.
while [ $# -gt 0 ]; do
  case "$1" in
    -o) shift 2 ;;
    *@*) shift; break ;;
    *) shift ;;
  esac
done
cmd="$*"
cmd="${cmd//\/mnt\/app/$FAKE_UNIT/mnt/app}"
cmd="${cmd//\/ifs\//$FAKE_UNIT/ifs/}"
cmd="${cmd//\/tmp\/mibsdk.log/$FAKE_UNIT/tmp/mibsdk.log}"
exec sh -c "$cmd"
"""

FAKE_NOOP = "#!/bin/sh\nexit 0\n"


def read(path):
    with open(path, "rb") as f:
        return f.read()


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def executable(path, text):
    write(path, text.encode("utf-8"))
    os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


class FakeUnit(object):
    def __init__(self, root, tracer_marker):
        self.root = root
        self.unit = os.path.join(root, "unit")
        self.lsd = os.path.join(self.unit, "mnt/app/eso/hmi/lsd")
        self.sdk = os.path.join(self.lsd, "mibsdk")
        bindir = os.path.join(root, "bin")
        executable(os.path.join(bindir, "ssh"), FAKE_SSH)
        executable(os.path.join(bindir, "mount"), FAKE_NOOP)
        executable(os.path.join(bindir, "slay"), FAKE_NOOP)
        self.dist = os.path.join(root, "dist")
        for j in SDK_JARS:
            write(os.path.join(self.dist, j), ("jar " + j).encode("ascii"))
        write(os.path.join(self.unit, "ifs/version_info.txt"), ("HMI = %s\n" % HMI).encode("ascii"))
        write(os.path.join(self.lsd, "bundles_dsitracer_enabled.properties"), read(STOCK))
        write(os.path.join(self.lsd, "DSITracer.jar"), b"tracer")
        write(os.path.join(self.lsd, "jars/stock.jar"), b"stock")
        os.makedirs(os.path.join(self.unit, "tmp"), exist_ok=True)
        if tracer_marker:
            write(self.marker(), b"")
        self.env = dict(os.environ)
        self.env["PATH"] = bindir + os.pathsep + self.env.get("PATH", "")
        self.env["FAKE_UNIT"] = self.unit
        self.env["MIBSDK_DIST"] = self.dist
        self.env["TMPDIR"] = root

    def marker(self):
        return os.path.join(self.lsd, "dsitracer_activated")

    def bundles(self):
        return read(os.path.join(self.lsd, "bundles_dsitracer_enabled.properties"))

    def autostart(self):
        lines = [l for l in self.bundles().splitlines() if l.startswith(b"Bundle.Startup.Autostart")]
        assert len(lines) == 1, lines
        return lines[0]

    def run(self, *args):
        return subprocess.run([TOOL] + list(args), env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=60)


class FakeUnitTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="mibsdk-dev-test.")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def unit(self, tracer_marker):
        return FakeUnit(self.tmp, tracer_marker)

    def ok(self, result):
        self.assertEqual(0, result.returncode, result.stderr.decode("utf-8", "replace"))
        return result


class InstallTest(FakeUnitTestCase):
    def test_tracer_off_unit_does_not_get_dsitracer_autostarted(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        self.assertEqual(b"Bundle.Startup.Autostart = MibSdk,FwUtilServices,Profiling,Tracing,ATIPImpl", u.autostart())
        props = read(os.path.join(u.sdk, "sdk.properties"))
        self.assertNotIn(b"chain.configurator", props)
        self.assertEqual(b"false\n", read(os.path.join(u.sdk, "stock/tracer.was")))
        self.assertTrue(os.path.exists(u.marker()), "lsd.sh reads the on-disk bundles file only with the marker")

    def test_tracer_on_unit_keeps_dsitracer_chained(self):
        u = self.unit(tracer_marker=True)
        self.ok(u.run("install"))
        self.assertEqual(b"Bundle.Startup.Autostart = DSITracer,MibSdk,FwUtilServices,Profiling,Tracing,ATIPImpl",
                         u.autostart())
        props = read(os.path.join(u.sdk, "sdk.properties"))
        self.assertIn(b"chain.configurator=de.audi.tghu.dsitrace.TraceConfigurator\n", props)

    def test_reinstall_uses_the_recorded_stock_state(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        self.ok(u.run("install"))  # the marker exists now, but tracer.was says it did not before the first install
        self.assertNotIn(b"DSITracer", u.autostart())
        self.assertNotIn(b"chain.configurator", read(os.path.join(u.sdk, "sdk.properties")))


class UninstallTest(FakeUnitTestCase):
    ENTRY = b"parking.sound.rear=bike-rack|PERSISTENT|on|off\n"

    def installed(self, ledger):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        if ledger is not None:
            write(os.path.join(u.sdk, "ledger.properties"), ledger)
        return u

    def assert_still_installed(self, u):
        self.assertIn(b"MibSdk", u.autostart())
        for j in SDK_JARS:
            self.assertTrue(os.path.exists(os.path.join(u.lsd, "jars", j)), j)
        self.assertTrue(os.path.isdir(u.sdk))

    def test_refuses_while_an_override_is_in_force(self):
        for args in (["uninstall"], ["uninstall", "--purge"]):
            u = self.installed(self.ENTRY)
            r = u.run(*args)
            err = r.stderr.decode("utf-8")
            self.assertNotEqual(0, r.returncode, args)
            self.assertIn("parking.sound.rear=bike-rack|PERSISTENT|on|off", err)
            self.assertIn("disable the plugin", err)
            self.assertIn("mibsdk-dev log", err)
            self.assertIn("--force", err)
            self.assert_still_installed(u)
            shutil.rmtree(self.tmp)
            os.makedirs(self.tmp)

    def test_force_uninstalls_with_a_loud_warning(self):
        u = self.installed(self.ENTRY)
        r = self.ok(u.run("uninstall", "--force", "--purge"))
        err = r.stderr.decode("utf-8")
        self.assertIn("WARNING", err)
        self.assertIn("parking.sound.rear=bike-rack|PERSISTENT|on|off", err)
        self.assertNotIn(b"MibSdk", u.autostart())
        self.assertFalse(os.path.exists(u.sdk))

    def test_empty_or_missing_ledger_uninstalls(self):
        for ledger in (None, b"", b"\n"):
            u = self.installed(ledger)
            r = self.ok(u.run("uninstall"))
            self.assertNotIn("WARNING", r.stderr.decode("utf-8"))
            self.assertNotIn(b"MibSdk", u.autostart())
            self.assertFalse(os.path.exists(u.marker()), "it was absent before the install")
            shutil.rmtree(self.tmp)
            os.makedirs(self.tmp)


if __name__ == "__main__":
    unittest.main()
