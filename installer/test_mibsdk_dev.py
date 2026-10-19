"""Drives installer/mibsdk-dev against a fake unit: a directory tree standing in for the head unit's file system,
reached through a fake `ssh` on PATH that runs each remote command locally with the unit's absolute paths
rewritten into that tree. Nothing ever touches a real device."""
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
import zipfile

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
cmd="${cmd//\/mnt\/persist/$FAKE_UNIT/mnt/persist}"
cmd="${cmd//\/ramdisk\//$FAKE_UNIT/ramdisk/}"
cmd="${cmd//\/ifs\//$FAKE_UNIT/ifs/}"
cmd="${cmd//\/tmp\/mibsdk.log/$FAKE_UNIT/tmp/mibsdk.log}"
exec sh -c "$cmd"
"""

FAKE_NOOP = "#!/bin/sh\nexit 0\n"

# mv that fails when an argument contains $FAKE_FAIL_MV: a write cut short (full or unmounted partition, lost link).
FAKE_MV = r"""#!/bin/sh
if [ -n "$FAKE_FAIL_MV" ]; then
  for a in "$@"; do
    case "$a" in *"$FAKE_FAIL_MV"*) echo "mv: injected failure" >&2; exit 1 ;; esac
  done
fi
exec /bin/mv "$@"
"""

# The MHI2Q has no find(1) (found on the bench: "sh: find: cannot execute"); shadow the host's so the tool cannot use it.
FAKE_NO_FIND = "#!/bin/sh\necho 'sh: find: cannot execute - No such file or directory' >&2\nexit 126\n"


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
        # Runtime-mutable state lives off /mnt/app (read-only while the HMI runs).
        self.persist = os.path.join(self.unit, "mnt/persist/mibsdk")
        self.ram = os.path.join(self.unit, "ramdisk/mibsdk")
        os.makedirs(os.path.join(self.unit, "mnt/persist"), exist_ok=True)  # the partition itself always exists
        bindir = os.path.join(root, "bin")
        executable(os.path.join(bindir, "ssh"), FAKE_SSH)
        executable(os.path.join(bindir, "mount"), FAKE_NOOP)
        executable(os.path.join(bindir, "slay"), FAKE_NOOP)
        executable(os.path.join(bindir, "find"), FAKE_NO_FIND)
        executable(os.path.join(bindir, "mv"), FAKE_MV)
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

    def bundles_path(self):
        return os.path.join(self.lsd, "bundles_dsitracer_enabled.properties")

    def bundles(self):
        return read(self.bundles_path())

    def autostart(self):
        lines = [l for l in self.bundles().splitlines() if l.startswith(b"Bundle.Startup.Autostart")]
        assert len(lines) == 1, lines
        return lines[0]

    def run(self, *args, **kw):
        env = dict(self.env)
        env["FAKE_FAIL_MV"] = kw.get("fail_mv", "")
        return subprocess.run([TOOL] + list(args), env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
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


    def test_creates_the_persistent_root_and_no_runtime_state_on_app(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        self.assertTrue(os.path.isdir(u.persist))
        self.assertTrue(os.path.isdir(os.path.join(u.sdk, "plugins")))
        for name in ("cache", "data", "boot.state", "ledger.properties"):
            self.assertFalse(os.path.exists(os.path.join(u.sdk, name)), name)

    def test_reinstall_moves_state_left_on_app_by_an_older_install(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        write(os.path.join(u.sdk, "data/bike-rack.properties"), b"active=false\n")
        write(os.path.join(u.sdk, "ledger.properties"), b"")
        write(os.path.join(u.sdk, "boot.state"), b"unhealthyBoots=1\n")
        write(os.path.join(u.sdk, "cache/hello-1.jar"), b"jar")
        self.ok(u.run("install"))
        self.assertEqual(b"active=false\n", read(os.path.join(u.persist, "data/bike-rack.properties")))
        self.assertTrue(os.path.exists(os.path.join(u.persist, "ledger.properties")))
        for name in ("cache", "data", "boot.state", "ledger.properties"):
            self.assertFalse(os.path.exists(os.path.join(u.sdk, name)), name)

    def test_reinstall_never_overwrites_persistent_state(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        write(os.path.join(u.persist, "data/p.properties"), b"new\n")
        write(os.path.join(u.sdk, "data/p.properties"), b"old\n")
        self.ok(u.run("install"))
        self.assertEqual(b"new\n", read(os.path.join(u.persist, "data/p.properties")))

    ENTRY = b"parking.sound.rear=bike-rack|PERSISTENT|on|off\n"
    OTHER = b"parking.sound.front=bike-rack|PERSISTENT|on|off\n"

    def old_ledger(self, u):
        return os.path.join(u.sdk, "ledger.properties")

    def new_ledger(self, u):
        return os.path.join(u.persist, "ledger.properties")

    def test_old_ledger_entries_are_merged_into_an_existing_ledger(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        write(self.new_ledger(u), self.OTHER)
        write(self.old_ledger(u), self.ENTRY)
        self.ok(u.run("install"))
        self.assertEqual(self.OTHER + self.ENTRY, read(self.new_ledger(u)))
        self.assertFalse(os.path.exists(self.old_ledger(u)), "merged and verified: the old ledger goes")

    def test_old_ledger_entry_that_conflicts_is_kept_where_it_is(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        newer = b"parking.sound.rear=bike-rack|PERSISTENT|off|on\n"
        write(self.new_ledger(u), newer)
        write(self.old_ledger(u), self.ENTRY)
        r = self.ok(u.run("install"))
        self.assertIn("parking.sound.rear", r.stderr.decode("utf-8"))
        self.assertEqual(newer, read(self.new_ledger(u)))
        self.assertEqual(self.ENTRY, read(self.old_ledger(u)), "not merged: never deleted")

    def test_failed_ledger_copy_loses_nothing_and_a_rerun_migrates_it(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        write(self.old_ledger(u), self.ENTRY)
        r = u.run("install", fail_mv="persist/mibsdk/.push.tmp")
        self.assertNotEqual(0, r.returncode)
        self.assertEqual(self.ENTRY, read(self.old_ledger(u)))
        self.assertFalse(os.path.exists(self.new_ledger(u)))
        self.ok(u.run("install"))
        self.assertEqual(self.ENTRY, read(self.new_ledger(u)))
        self.assertFalse(os.path.exists(self.old_ledger(u)))

    def test_failed_data_copy_loses_nothing_and_a_rerun_migrates_it(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        write(os.path.join(u.sdk, "data/a.properties"), b"a=1\n")
        write(os.path.join(u.sdk, "data/b.properties"), b"b=2\n")
        r = u.run("install", fail_mv="b.properties.migrate.tmp")
        self.assertNotEqual(0, r.returncode)
        self.assertEqual(b"a=1\n", read(os.path.join(u.sdk, "data/a.properties")))
        self.assertEqual(b"b=2\n", read(os.path.join(u.sdk, "data/b.properties")))
        self.assertFalse(os.path.exists(os.path.join(u.persist, "data/b.properties")), "never a partial file")
        self.ok(u.run("install"))
        self.assertEqual(b"a=1\n", read(os.path.join(u.persist, "data/a.properties")))
        self.assertEqual(b"b=2\n", read(os.path.join(u.persist, "data/b.properties")))
        self.assertEqual([], sorted(n for n in os.listdir(os.path.join(u.persist, "data")) if n.endswith(".tmp")))
        self.assertFalse(os.path.exists(os.path.join(u.sdk, "data")))

    def test_refuses_when_carplay_stock_files_exist_anywhere_under_hmi(self):
        u = self.unit(tracer_marker=False)
        write(os.path.join(u.unit, "mnt/app/eso/hmi/lsd/kzbs/deep/foo.kzb.carplay-stock"), b"x")
        r = u.run("install")
        self.assertNotEqual(0, r.returncode)
        self.assertIn(b"carplay-rgi is installed", r.stderr)
        self.assertFalse(os.path.exists(u.sdk), "nothing may change on a refused install")


class UninstallTest(FakeUnitTestCase):
    ENTRY = b"parking.sound.rear=bike-rack|PERSISTENT|on|off\n"

    def installed(self, ledger):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        if ledger is not None:
            write(os.path.join(u.persist, "ledger.properties"), ledger)
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
        self.assertFalse(os.path.exists(u.persist), "--purge removes the plugin data too")

    def test_refuses_on_an_override_left_in_the_old_ledger_location(self):
        u = self.installed(None)
        write(os.path.join(u.sdk, "ledger.properties"), self.ENTRY)  # an install from before the move
        r = u.run("uninstall")
        self.assertNotEqual(0, r.returncode)
        self.assertIn("parking.sound.rear=bike-rack|PERSISTENT|on|off", r.stderr.decode("utf-8"))

    def test_default_uninstall_keeps_plugin_data_purge_removes_it(self):
        u = self.installed(None)
        write(os.path.join(u.persist, "data/bike-rack.properties"), b"active=false\n")
        write(os.path.join(u.ram, "cache/hello-1.jar"), b"jar")
        self.ok(u.run("uninstall"))
        self.assertEqual(b"active=false\n", read(os.path.join(u.persist, "data/bike-rack.properties")))
        self.ok(u.run("install"))
        self.ok(u.run("uninstall", "--purge"))
        self.assertFalse(os.path.exists(u.persist))
        self.assertFalse(os.path.exists(u.ram))
        self.assertFalse(os.path.exists(u.sdk))
        self.assertTrue(os.path.isdir(os.path.join(u.unit, "mnt/persist")), "only our directory goes")

    def test_uninstall_restores_the_bundles_file_mode_and_leaves_the_marker_alone(self):
        """F4: the stock bundles file is -rwxrwxrwx; push_file creates a new 0644 file, and touch bumped the marker."""
        u = self.unit(tracer_marker=True)
        os.chmod(u.bundles_path(), 0o777)
        os.utime(u.marker(), (1000000000, 1000000000))
        self.ok(u.run("install"))
        self.assertEqual(0o777, stat.S_IMODE(os.stat(u.bundles_path()).st_mode), "install keeps the mode too")
        self.ok(u.run("uninstall"))
        self.assertEqual(read(STOCK), u.bundles())
        self.assertEqual(0o777, stat.S_IMODE(os.stat(u.bundles_path()).st_mode))
        self.assertEqual(1000000000, int(os.stat(u.marker()).st_mtime), "the marker existed: never touched")

    def test_empty_or_missing_ledger_uninstalls(self):
        for ledger in (None, b"", b"\n"):
            u = self.installed(ledger)
            r = self.ok(u.run("uninstall"))
            self.assertNotIn("WARNING", r.stderr.decode("utf-8"))
            self.assertNotIn(b"MibSdk", u.autostart())
            self.assertFalse(os.path.exists(u.marker()), "it was absent before the install")
            shutil.rmtree(self.tmp)
            os.makedirs(self.tmp)


class SafeModeTest(FakeUnitTestCase):
    def test_off_writes_boot_guard_reset_content(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        write(os.path.join(u.sdk, "SAFE_MODE"), b"")
        write(os.path.join(u.persist, "boot.state"), b"unhealthyBoots=3\nlastShutdownClean=false\nwrapBoots=3\n")
        self.ok(u.run("safe-mode", "off"))
        self.assertFalse(os.path.exists(os.path.join(u.sdk, "SAFE_MODE")))
        # Must equal BootGuard.reset()'s file (BootGuardTest.resetLeavesSafeMode).
        self.assertEqual(b"unhealthyBoots=0\nlastShutdownClean=true\nwrapBoots=0\n",
                         read(os.path.join(u.persist, "boot.state")))
        self.assertFalse(os.path.exists(os.path.join(u.sdk, "boot.state")))


class PushTest(FakeUnitTestCase):
    def plugin_jar(self, plugin_id):
        path = os.path.join(self.tmp, plugin_id + ".jar")
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("plugin.json", '{"id": "%s"}' % plugin_id)
        return path

    def test_each_push_writes_a_fresh_reload_marker(self):
        """The runtime never deletes markers (read-only /mnt/app); it reloads when a marker's content changes."""
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        jar = self.plugin_jar("hello")
        marker = os.path.join(u.sdk, "plugins/hello.reload")
        self.ok(u.run("push", jar))
        self.assertEqual(read(jar), read(os.path.join(u.sdk, "plugins/hello.jar")))
        first = read(marker)
        self.ok(u.run("push", jar))
        self.assertNotEqual(first, read(marker))
        self.assertTrue(read(marker).strip(), "a non-empty token")


if __name__ == "__main__":
    unittest.main()
