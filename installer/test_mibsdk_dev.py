"""Drives installer/mibsdk-dev against a fake unit: a directory tree standing in for the head unit's file system,
reached through a fake `ssh` on PATH that runs each remote command locally with the unit's absolute paths
rewritten into that tree. Nothing ever touches a real device."""
import os
import shutil
import stat
import subprocess
import hashlib
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
# Test hook: a file that changes on disk once the tool has connected (after it verified its input).
if [ -n "$FAKE_REWRITE" ] && [ "$cmd" = "echo ok" ]; then printf tampered >"$FAKE_REWRITE"; fi
cmd="${cmd//\/mnt\/app/$FAKE_UNIT/mnt/app}"
cmd="${cmd//\/mnt\/persist/$FAKE_UNIT/mnt/persist}"
cmd="${cmd//\/ramdisk\//$FAKE_UNIT/ramdisk/}"
cmd="${cmd//\/ifs\//$FAKE_UNIT/ifs/}"
cmd="${cmd//\/tmp\/mibsdk.log/$FAKE_UNIT/tmp/mibsdk.log}"
# Like OpenSSH, read all of our stdin (it would forward it to the remote command): a caller's `while read` loop loses the rest.
cat | sh -c "$cmd"
exit ${PIPESTATUS[1]}
"""

FAKE_NOOP = "#!/bin/sh\nexit 0\n"

# mv that fails when an argument contains $FAKE_FAIL_MV: a write cut short (full or unmounted partition, lost link);
# $FAKE_FAIL_MV_SILENT fails the same way but exits 0.
# Like QNX's mv (seen on the bench), it exits 0 when it cannot replace a target on a read-only /mnt/app. With
# $FAKE_RO_AFTER_MV, another process (mibpkg's supervisor on the bench) puts /mnt/app back to read-only right after a
# move whose arguments contain it.
FAKE_MV = r"""#!/bin/sh
if [ -n "$FAKE_FAIL_MV" ]; then
  for a in "$@"; do
    case "$a" in *"$FAKE_FAIL_MV"*) echo "mv: injected failure" >&2; exit 1 ;; esac
  done
fi
if [ -n "$FAKE_FAIL_MV_SILENT" ]; then
  for a in "$@"; do
    case "$a" in *"$FAKE_FAIL_MV_SILENT"*) echo "mv: injected failure (status 0, like QNX)" >&2; exit 0 ;; esac
  done
fi
for a in "$@"; do last="$a"; done
case "$last" in
  "$FAKE_UNIT"/mnt/app/*)
    if [ -e "$FAKE_UNIT/app.ro" ]; then echo "mv: Can't unlink file $last : Read-only file system" >&2; exit 0; fi ;;
esac
/bin/mv "$@" || exit $?
if [ -n "$FAKE_RO_AFTER_MV" ]; then
  for a in "$@"; do
    case "$a" in *"$FAKE_RO_AFTER_MV"*) touch "$FAKE_UNIT/app.ro" ;; esac
  done
fi
exit 0
"""

# mount: only tracks whether /mnt/app is read-only (the fake mv honours it).
FAKE_MOUNT = r"""#!/bin/sh
case "$1" in
  -uw) rm -f "$FAKE_UNIT/app.ro" ;;
  -ur) touch "$FAKE_UNIT/app.ro" ;;
esac
exit 0
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


def stamp(path, kind, jars, attrs=None):
    """Writes a check stamp in the format of build-logic's CheckStamp (version 1)."""
    lines = ["format=1", "kind=" + kind, "checks=classVersion48,linkCheck,permissionScan"]
    lines += ["%s=%s" % (k, v) for k, v in sorted((attrs or {}).items())]
    for jar in sorted(jars, key=os.path.basename):
        lines.append("sha256.%s=%s" % (os.path.basename(jar), hashlib.sha256(read(jar)).hexdigest()))
    write(path, ("\n".join(lines) + "\n").encode("latin-1"))


def write_jar(path, names):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w") as z:
        for n in names:
            z.writestr(n, b"x")


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
        executable(os.path.join(bindir, "mount"), FAKE_MOUNT)
        executable(os.path.join(bindir, "slay"), FAKE_NOOP)
        executable(os.path.join(bindir, "find"), FAKE_NO_FIND)
        executable(os.path.join(bindir, "mv"), FAKE_MV)
        self.dist = os.path.join(root, "dist")
        for j in SDK_JARS:
            write(os.path.join(self.dist, j), ("jar " + j).encode("ascii"))
        stamp(os.path.join(self.dist, "mibsdk-unit.stamp"), "unit", [os.path.join(self.dist, j) for j in SDK_JARS])
        write(os.path.join(self.unit, "ifs/version_info.txt"), ("HMI = %s\n" % HMI).encode("ascii"))
        write(os.path.join(self.lsd, "bundles_dsitracer_enabled.properties"), read(STOCK))
        write(os.path.join(self.lsd, "DSITracer.jar"), b"tracer")
        write_jar(os.path.join(self.lsd, "jars/stock.jar"), ["de/audi/other/Thing.class"])
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
        env["FAKE_RO_AFTER_MV"] = kw.get("ro_after_mv", "")
        env["FAKE_REWRITE"] = kw.get("rewrite", "")
        env["FAKE_FAIL_MV_SILENT"] = kw.get("fail_mv_silent", "")
        return subprocess.run([TOOL] + list(args), env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
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
    def test_refuses_a_dist_without_a_stamp(self):
        u = self.unit(tracer_marker=False)
        os.remove(os.path.join(u.dist, "mibsdk-unit.stamp"))
        r = u.run("install")
        self.assertNotEqual(0, r.returncode)
        err = r.stderr.decode("utf-8")
        self.assertIn("not a checked build (missing)", err)
        self.assertIn(":dist:assembleUnit", err)
        self.assertNotIn(b"MibSdk", u.autostart())
        self.assertFalse(os.path.exists(u.sdk), "nothing changed on the unit")

    def test_pushes_the_bytes_it_verified_not_a_jar_rewritten_afterwards(self):
        u = self.unit(tracer_marker=False)
        jar = os.path.join(u.dist, "mibsdk-runtime.jar")
        original = read(jar)
        self.ok(u.run("install", rewrite=jar))
        self.assertEqual(original, read(os.path.join(u.lsd, "jars/mibsdk-runtime.jar")))

    def test_refuses_a_jar_that_does_not_match_its_stamp(self):
        u = self.unit(tracer_marker=False)
        write(os.path.join(u.dist, "mibsdk-runtime.jar"), b"rebuilt without the checks")
        r = u.run("install")
        self.assertNotEqual(0, r.returncode)
        err = r.stderr.decode("utf-8")
        self.assertIn("hash mismatch for mibsdk-runtime.jar", err)
        self.assertIn(":dist:assembleUnit", err)
        self.assertNotIn(b"MibSdk", u.autostart())
        self.assertFalse(os.path.exists(u.sdk), "nothing changed on the unit")

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

    ENTRY = b"parking.soundDirections=bike-rack|PERSISTENT|F1R1L1X1|F0R0L0X0|A\n"
    OTHER = b"parking.soundLevel=bike-rack|PERSISTENT|F1R1L1X1|F0R0L0X0|A\n"

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
        newer = b"parking.soundDirections=bike-rack|PERSISTENT|F0R0L0X0|F1R1L1X1|A\n"
        write(self.new_ledger(u), newer)
        write(self.old_ledger(u), self.ENTRY)
        r = self.ok(u.run("install"))
        self.assertIn("parking.soundDirections", r.stderr.decode("utf-8"))
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
        self.assertFalse(os.path.exists(os.path.join(u.persist, ".push.tmp")), "no temp file left behind")
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

    def test_data_move_that_fails_with_status_0_keeps_the_old_data(self):
        """QNX mv exits 0 when it fails: the old data/ goes only once each file is really in place."""
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        write(os.path.join(u.sdk, "data/b.properties"), b"b=2\n")
        r = u.run("install", fail_mv_silent="b.properties.migrate.tmp")
        self.assertNotEqual(0, r.returncode)
        self.assertEqual(b"b=2\n", read(os.path.join(u.sdk, "data/b.properties")))
        self.ok(u.run("install"))
        self.assertEqual(b"b=2\n", read(os.path.join(u.persist, "data/b.properties")))
        self.assertFalse(os.path.exists(os.path.join(u.sdk, "data")))

    def test_install_sweeps_stale_push_temp_files(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        for d in (u.lsd, os.path.join(u.lsd, "jars"), u.sdk, os.path.join(u.sdk, "plugins"), u.persist):
            write(os.path.join(d, ".push.tmp"), b"partial")
        self.ok(u.run("install"))
        for d in (u.lsd, os.path.join(u.lsd, "jars"), u.sdk, os.path.join(u.sdk, "plugins"), u.persist):
            self.assertFalse(os.path.exists(os.path.join(d, ".push.tmp")), d)

    def test_refuses_when_carplay_stock_files_exist_anywhere_under_hmi(self):
        u = self.unit(tracer_marker=False)
        write(os.path.join(u.unit, "mnt/app/eso/hmi/lsd/kzbs/deep/foo.kzb.carplay-stock"), b"x")
        r = u.run("install")
        self.assertNotEqual(0, r.returncode)
        self.assertIn(b"carplay-rgi is installed", r.stderr)
        self.assertFalse(os.path.exists(u.sdk), "nothing may change on a refused install")

    def test_refuses_jar_defining_owned_class(self):
        for cls in ("de/audi/tghu/car/hmi/Activator.class", "de/audi/atip/model/CarModelBank.class",
                    "de/audi/atip/model/CarModelBank$1.class"):
            u = self.unit(tracer_marker=False)
            write_jar(os.path.join(u.lsd, "jars/aa-clean.jar"), ["a/B.class"])
            write_jar(os.path.join(u.lsd, "jars/zz-other-mod.jar"), ["a/B.class", cls])
            before = u.bundles()
            r = u.run("install")
            self.assertNotEqual(0, r.returncode)
            self.assertIn("mibsdk-dev: zz-other-mod.jar defines %s, which the SDK owns; nothing changed" % cls,
                          r.stderr.decode("utf-8"))
            self.assertEqual(before, u.bundles())
            self.assertFalse(os.path.exists(u.sdk), "nothing changed on the unit")
            shutil.rmtree(self.tmp)
            os.makedirs(self.tmp)

    def test_ignores_sdk_jars_in_ownership_guard(self):
        u = self.unit(tracer_marker=False)
        write_jar(os.path.join(u.lsd, "jars/mibsdk-adapter-hqc.jar"), ["de/audi/tghu/car/hmi/Activator.class"])
        write_jar(os.path.join(u.lsd, "jars/other-mod.jar"), ["de/audi/tghu/car/hmiextra/X.class", "a/B.txt"])
        self.ok(u.run("install"))

    def test_refuses_while_spike_jar_present(self):
        u = self.unit(tracer_marker=False)
        write_jar(os.path.join(u.lsd, "jars/mibsdk-spike-ui.jar"), ["mibsdk/spike/ui/SpikeCarActivator.class"])
        r = u.run("install")
        self.assertNotEqual(0, r.returncode)
        self.assertIn("mibsdk-dev: spike jar mibsdk-spike-ui.jar is installed; run spikes/hmi-ui/run.sh uninstall first",
                      r.stderr.decode("utf-8"))
        self.assertFalse(os.path.exists(u.sdk), "nothing changed on the unit")

    def test_guard_checks_every_jar_not_just_the_first(self):
        u = self.unit(tracer_marker=False)
        for n in ("a1", "b2", "c3"):
            write_jar(os.path.join(u.lsd, "jars/%s.jar" % n), ["x/Y.class"])
        write_jar(os.path.join(u.lsd, "jars/zz-last.jar"), ["de/audi/tghu/car/hmi/Z.class"])
        r = u.run("install")
        self.assertNotEqual(0, r.returncode)
        self.assertIn(b"zz-last.jar defines de/audi/tghu/car/hmi/Z.class", r.stderr)

    def test_guard_only_looks_at_jars(self):
        u = self.unit(tracer_marker=False)
        write(os.path.join(u.lsd, "jars/lang_data.zip"), b"not a zip")
        write(os.path.join(u.lsd, "jars/old.jar.bak"), b"not a zip")
        self.ok(u.run("install"))

    def test_guard_fails_closed_on_an_unreadable_jar(self):
        u = self.unit(tracer_marker=False)
        write(os.path.join(u.lsd, "jars/broken.jar"), b"not a zip")
        r = u.run("install")
        self.assertNotEqual(0, r.returncode)
        self.assertIn(b"broken.jar", r.stderr)
        self.assertFalse(os.path.exists(u.sdk))

    def test_guard_refuses_a_symlinked_jar(self):
        u = self.unit(tracer_marker=False)
        os.symlink(os.path.join(u.lsd, "jars/stock.jar"), os.path.join(u.lsd, "jars/link.jar"))
        r = u.run("install")
        self.assertNotEqual(0, r.returncode)
        self.assertIn(b"link.jar is a symlink", r.stderr)
        self.assertFalse(os.path.exists(u.sdk))

    def test_guard_refuses_a_jar_name_with_a_space(self):
        u = self.unit(tracer_marker=False)
        write_jar(os.path.join(u.lsd, "jars/my mod.jar"), ["x/Y.class"])
        r = u.run("install")
        self.assertNotEqual(0, r.returncode)
        self.assertIn(b"has a space", r.stderr)
        self.assertFalse(os.path.exists(u.sdk))

    def test_nonstock_hmicar_changes_nothing_at_all(self):
        for bad in (b"HMICar=x.Y", b"HMICar = de.audi.tghu.car.hmi.Activator",
                    b"HMICar=de.audi.tghu.car.hmi.Activator ", b"HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator"):
            u = self.unit(tracer_marker=False)
            write(u.bundles_path(), u.bundles().replace(b"HMICar=de.audi.tghu.car.hmi.Activator", bad))
            before = u.bundles()
            r = u.run("install")
            self.assertNotEqual(0, r.returncode)
            self.assertIn(b"HMICar is not stock", r.stderr)
            self.assertEqual(before, u.bundles())
            self.assertFalse(os.path.exists(u.sdk), "no backup written: " + bad.decode())
            self.assertFalse(os.path.exists(os.path.join(u.unit, "app.ro")), "/mnt/app was never remounted")
            shutil.rmtree(self.tmp)
            os.makedirs(self.tmp)

    def test_upgrade_refuses_a_foreign_live_hmicar(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        write(u.bundles_path(), u.bundles().replace(b"mibsdk.adapter.hqc.ui.SdkCarActivator", b"other.Act"))
        before = u.bundles()
        r = u.run("install")
        self.assertNotEqual(0, r.returncode)
        self.assertIn(b"HMICar is not stock", r.stderr)
        self.assertEqual(before, u.bundles())

    def test_upgrade_with_the_sdk_value_live_is_fine(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        self.ok(u.run("install"))

    def test_refuses_a_backup_that_already_holds_the_sdk_value(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        bk = os.path.join(u.sdk, "stock/bundles_dsitracer_enabled.properties")
        write(bk, read(bk).replace(b"HMICar=de.audi.tghu.car.hmi.Activator", b"HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator"))
        r = u.run("install")
        self.assertNotEqual(0, r.returncode)
        self.assertIn(b"HMICar is not stock", r.stderr)

    def test_install_points_hmicar_at_the_sdk_keeping_crlf(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        self.assertIn(b"HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator\r\n", u.bundles())
        self.assertIn(b"ui.enabled=true\n", read(os.path.join(u.sdk, "sdk.properties")))

    def test_refuses_a_nonstock_hmicar_line(self):
        u = self.unit(tracer_marker=False)
        write(u.bundles_path(), u.bundles().replace(b"HMICar=de.audi.tghu.car.hmi.Activator", b"HMICar=x.Y"))
        r = u.run("install")
        self.assertNotEqual(0, r.returncode)
        self.assertNotIn(b"mibsdk.adapter.hqc.ui", u.bundles())
        self.assertFalse(os.path.exists(u.sdk))


class UninstallTest(FakeUnitTestCase):
    ENTRY = b"parking.soundDirections=bike-rack|PERSISTENT|F1R1L1X1|F0R0L0X0|A\n"

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
            self.assertIn("parking.soundDirections=bike-rack|PERSISTENT|F1R1L1X1|F0R0L0X0|A", err)
            self.assertIn("mibsdk-dev safe-mode on && mibsdk-dev restart", err)
            self.assertIn("safe-mode off", err)
            self.assertIn("mibsdk-dev log", err)
            self.assertIn("--force", err)
            self.assert_still_installed(u)
            shutil.rmtree(self.tmp)
            os.makedirs(self.tmp)

    def test_refusal_shows_a_pending_restore_line(self):
        line = "parking.soundDirections=bike-rack|PERSISTENT|F1R1L1X1|F0R0L0X0|R"
        u = self.installed((line + "\n").encode("ascii"))
        r = u.run("uninstall")
        self.assertNotEqual(0, r.returncode)
        self.assertIn(line, r.stderr.decode("utf-8"))
        self.assert_still_installed(u)

    def test_force_uninstalls_with_a_loud_warning(self):
        u = self.installed(self.ENTRY)
        r = self.ok(u.run("uninstall", "--force", "--purge"))
        err = r.stderr.decode("utf-8")
        self.assertIn("WARNING", err)
        self.assertIn("parking.soundDirections=bike-rack|PERSISTENT|F1R1L1X1|F0R0L0X0|A", err)
        self.assertNotIn(b"MibSdk", u.autostart())
        self.assertFalse(os.path.exists(u.sdk))
        self.assertFalse(os.path.exists(u.persist), "--purge removes the plugin data too")

    def test_refuses_on_an_override_left_in_the_old_ledger_location(self):
        u = self.installed(None)
        write(os.path.join(u.sdk, "ledger.properties"), self.ENTRY)  # an install from before the move
        r = u.run("uninstall")
        self.assertNotEqual(0, r.returncode)
        self.assertIn("parking.soundDirections=bike-rack|PERSISTENT|F1R1L1X1|F0R0L0X0|A", r.stderr.decode("utf-8"))

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

    def test_uninstall_restores_hmicar_byte_identical(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        self.assertNotEqual(read(STOCK), u.bundles())
        self.ok(u.run("uninstall"))
        self.assertEqual(read(STOCK), u.bundles())
        self.assertIn(b"HMICar=de.audi.tghu.car.hmi.Activator\r\n", u.bundles())

    def test_backup_without_a_recorded_mode_restores_the_stock_mode_0777(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        os.remove(os.path.join(u.sdk, "stock/bundles.mode"))  # a backup from an install before the mode was kept
        os.chmod(u.bundles_path(), 0o644)
        self.ok(u.run("uninstall"))
        self.assertEqual(0o777, stat.S_IMODE(os.stat(u.bundles_path()).st_mode))

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


class BootStateTest(FakeUnitTestCase):
    def test_set_wrap_keeps_other_keys(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        write(os.path.join(u.persist, "boot.state"), b"unhealthyBoots=2\nlastShutdownClean=false\nwrapBoots=0\nextra=1\n")
        self.ok(u.run("boot-state", "set-wrap", "3"))
        self.assertEqual(b"unhealthyBoots=2\nlastShutdownClean=false\nwrapBoots=3\nextra=1\n",
                         read(os.path.join(u.persist, "boot.state")))

    def test_set_wrap_without_a_file_uses_the_reset_keys(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        self.ok(u.run("boot-state", "set-wrap", "2"))
        self.assertEqual(b"unhealthyBoots=0\nlastShutdownClean=true\nwrapBoots=2\n",
                         read(os.path.join(u.persist, "boot.state")))

    def test_set_wrap_rejects_a_bad_count(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        self.assertNotEqual(0, u.run("boot-state", "set-wrap", "x").returncode)
        self.assertNotEqual(0, u.run("boot-state", "set-wrap").returncode)


class StatusTest(FakeUnitTestCase):
    def test_prints_the_installed_hmicar_line(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        write(os.path.join(u.unit, "tmp/mibsdk.log"), b"x STATUS ui=on\n")
        r = self.ok(u.run("status"))
        out = r.stdout.decode("utf-8")
        self.assertEqual("HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator\nx STATUS ui=on\n", out)


class StatusEdgeTest(FakeUnitTestCase):
    def test_missing_hmicar_line_and_unreadable_bundles(self):
        u = self.unit(tracer_marker=False)
        write(os.path.join(u.unit, "tmp/mibsdk.log"), b"x STATUS ui=on\n")
        write(u.bundles_path(), b"Bundle.Startup.Autostart = A\r\n")
        self.assertEqual("HMICar=<missing>\nx STATUS ui=on\n", self.ok(u.run("status")).stdout.decode())
        os.remove(u.bundles_path())
        r = self.ok(u.run("status"))
        self.assertEqual("x STATUS ui=on\n", r.stdout.decode())
        self.assertIn(b"warning: cannot read", r.stderr)


class DataClearTest(FakeUnitTestCase):
    def test_removes_only_that_plugins_data(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        data = os.path.join(u.persist, "data")
        write(os.path.join(data, "hello.properties"), b"x=1\n")
        write(os.path.join(data, "hello.properties.tmp"), b"x=")
        write(os.path.join(data, "hello2.properties"), b"y=1\n")
        write(os.path.join(u.persist, "ledger.properties"), b"k=hello|PERSISTENT|a|b|A\n")
        write(os.path.join(u.persist, "boot.state"), b"unhealthyBoots=1\n")
        r = self.ok(u.run("data-clear", "hello"))
        self.assertIn("hello", r.stdout.decode("utf-8"))
        self.assertEqual(["hello2.properties"], sorted(os.listdir(data)))
        self.assertEqual(b"k=hello|PERSISTENT|a|b|A\n", read(os.path.join(u.persist, "ledger.properties")))
        self.assertEqual(b"unhealthyBoots=1\n", read(os.path.join(u.persist, "boot.state")))
        self.ok(u.run("data-clear", "hello"))  # nothing left: still fine

    def test_rejects_a_bad_id(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        write(os.path.join(u.persist, "data/x.properties"), b"x=1\n")
        for bad in ("../x", "*", ""):
            r = u.run("data-clear", bad)
            self.assertNotEqual(0, r.returncode, bad)
        self.assertTrue(os.path.exists(os.path.join(u.persist, "data/x.properties")))


class PushTest(FakeUnitTestCase):
    def plugin_jar(self, plugin_id, content="", unrestricted="false", stamped=True, stamp_id=None):
        path = os.path.join(self.tmp, plugin_id + ".jar")
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("plugin.json", '{"id": "%s"}' % plugin_id)
            if content:
                z.writestr("content.txt", content)
        if stamped:
            stamp(path + ".stamp", "plugin", [path],
                  {"plugin.id": stamp_id or plugin_id, "plugin.unrestricted": unrestricted})
        return path

    def assert_refused(self, u, r, plugin_id, reason):
        self.assertNotEqual(0, r.returncode)
        err = r.stderr.decode("utf-8")
        self.assertIn("has no valid check stamp (%s)" % reason, err)
        self.assertIn(":mibPluginJar", err)
        self.assertFalse(os.path.exists(os.path.join(u.sdk, "plugins", plugin_id + ".jar")))

    def test_pushes_the_bytes_it_verified_not_a_jar_rewritten_afterwards(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        jar = self.plugin_jar("hello")
        original = read(jar)
        self.ok(u.run("push", jar, rewrite=jar))
        self.assertEqual(original, read(os.path.join(u.sdk, "plugins/hello.jar")))

    def test_refuses_a_stamp_with_a_duplicate_key_or_no_checks(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        jar = self.plugin_jar("hello")
        good = read(jar + ".stamp")
        for bad in (good + b"plugin.id=hello\n", good.replace(b"checks=classVersion48,linkCheck,permissionScan", b"checks=")):
            write(jar + ".stamp", bad)
            self.assert_refused(u, u.run("push", jar), "hello", "format")

    def test_refuses_a_jar_without_a_stamp(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        self.assert_refused(u, u.run("push", self.plugin_jar("hello", stamped=False)), "hello", "missing")

    def test_refuses_a_stamp_for_another_id(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        r = u.run("push", self.plugin_jar("hello", stamp_id="other"))
        self.assert_refused(u, r, "hello", "id other != hello")

    def test_refuses_a_stale_stamp(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        jar = self.plugin_jar("hello")
        self.plugin_jar("hello", content="rebuilt after stamping", stamped=False)
        self.assert_refused(u, u.run("push", jar), "hello", "hash mismatch for hello.jar")

    def test_warns_loudly_for_an_unrestricted_plugin(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        r = self.ok(u.run("push", self.plugin_jar("hello", unrestricted="true")))
        self.assertIn("WARNING: hello declares platform.unrestricted", r.stderr.decode("utf-8"))
        self.assertTrue(os.path.exists(os.path.join(u.sdk, "plugins/hello.jar")))

    def test_a_restricted_plugin_gets_no_warning(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        r = self.ok(u.run("push", self.plugin_jar("hello")))
        self.assertNotIn("unrestricted", r.stderr.decode("utf-8"))

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

    def test_a_failed_push_leaves_no_temp_file(self):
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        jar = self.plugin_jar("hello")
        r = u.run("push", jar, fail_mv="plugins/hello.jar")
        self.assertNotEqual(0, r.returncode)
        self.assertFalse(os.path.exists(os.path.join(u.sdk, "plugins/.push.tmp")))
        self.assertFalse(os.path.exists(os.path.join(u.sdk, "plugins/hello.jar")))

    def test_push_survives_another_process_remounting_app_read_only(self):
        """Bench: mibpkg's supervisor put /mnt/app back to read-only between the jar and the marker of one push."""
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        jar = self.plugin_jar("hello")
        self.ok(u.run("push", jar, ro_after_mv="plugins/hello.jar"))
        self.assertEqual(read(jar), read(os.path.join(u.sdk, "plugins/hello.jar")))
        self.assertTrue(read(os.path.join(u.sdk, "plugins/hello.reload")).strip())
        self.assertFalse(os.path.exists(os.path.join(u.sdk, "plugins/.push.tmp")))

    def test_a_move_that_fails_with_status_0_fails_the_push(self):
        """QNX mv exits 0 when it cannot replace the target; push must judge the result, not the status."""
        u = self.unit(tracer_marker=False)
        self.ok(u.run("install"))
        jar = self.plugin_jar("hello")
        write(os.path.join(u.unit, "app.ro"), b"")
        executable(os.path.join(u.root, "bin", "mount"), FAKE_NOOP)  # /mnt/app stays read-only whatever we do
        r = u.run("push", jar)
        self.assertNotEqual(0, r.returncode)
        self.assertIn(b"failed to move", r.stderr)
        self.assertFalse(os.path.exists(os.path.join(u.sdk, "plugins/hello.jar")))


class RemoveTest(FakeUnitTestCase):
    def setUp(self):
        super(RemoveTest, self).setUp()
        self.u = self.unit(tracer_marker=False)
        self.u.env["MIBSDK_REMOVE_WAIT_S"] = "0"
        self.ok(self.u.run("install"))

    def jar(self, plugin_id):
        path = os.path.join(self.tmp, plugin_id + ".jar")
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("plugin.json", '{"id": "%s"}' % plugin_id)
        stamp(path + ".stamp", "plugin", [path], {"plugin.id": plugin_id, "plugin.unrestricted": "false"})
        return path

    def installed(self, plugin_id):
        self.ok(self.u.run("push", self.jar(plugin_id)))
        self.ok(self.u.run("grant", plugin_id, "vehicle.read"))

    def grants(self):
        return read(os.path.join(self.u.sdk, "grants.properties")).decode("utf-8")

    def ledger(self, text):
        write(os.path.join(self.u.persist, "ledger.properties"), text.encode("utf-8"))

    def test_removes_the_jar_and_grant_and_requests_a_reload(self):
        self.installed("hello")
        marker = os.path.join(self.u.sdk, "plugins/hello.reload")
        before = read(marker)
        r = self.ok(self.u.run("remove", "hello"))
        self.assertFalse(os.path.exists(os.path.join(self.u.sdk, "plugins/hello.jar")))
        self.assertNotIn("hello=", self.grants())
        self.assertNotEqual(before, read(marker))
        self.assertTrue(read(marker).strip())
        self.assertTrue(os.path.exists(os.path.join(self.u.unit, "app.ro")))
        self.assertIn("removed hello", r.stdout.decode("utf-8"))
        self.assertIn("hello has no overrides in the ledger", r.stdout.decode("utf-8"))

    def test_drops_the_plugins_off_switch(self):
        self.installed("hello")
        plugins = os.path.join(self.u.persist, "plugins.properties")
        write(plugins, b"hello.off=true\nhello-2.off=true\nother.off=true\n")
        self.ok(self.u.run("remove", "hello"))
        self.assertEqual(b"hello-2.off=true\nother.off=true\n", read(plugins))

    def test_plugins_properties_is_pushed_before_the_reload_marker(self):
        self.installed("hello")
        write(os.path.join(self.u.persist, "plugins.properties"), b"hello.off=true\n")
        r = self.u.run("remove", "hello", fail_mv="hello.reload")
        self.assertNotEqual(0, r.returncode)
        self.assertEqual(b"", read(os.path.join(self.u.persist, "plugins.properties")))

    def test_leaves_a_plugin_with_a_longer_id_alone(self):
        self.installed("bike")
        self.installed("bike-rack")
        write(os.path.join(self.u.persist, "data/bike-rack.properties"), b"x=1\n")
        self.ledger("a.key=bike|PERSISTENT|F1|F0|R\nb.key=bike-rack|PERSISTENT|F1|F0|A\n")
        r = self.ok(self.u.run("remove", "bike"))
        self.assertTrue(os.path.exists(os.path.join(self.u.sdk, "plugins/bike-rack.jar")))
        self.assertIn("bike-rack=vehicle.read", self.grants())
        self.assertNotIn("bike=vehicle", self.grants().replace("bike-rack=", ""))
        self.assertTrue(os.path.exists(os.path.join(self.u.persist, "data/bike-rack.properties")))
        self.assertIn(b"b.key=bike-rack|", read(os.path.join(self.u.persist, "ledger.properties")))
        err = r.stderr.decode("utf-8")
        self.assertIn("a.key=bike|PERSISTENT|F1|F0|R", err)
        self.assertNotIn("b.key", err)

    def test_keeps_data_unless_purge_data(self):
        self.installed("hello")
        self.installed("other")
        for i in ("hello", "other"):
            write(os.path.join(self.u.persist, "data/%s.properties" % i), b"x=1\n")
        self.ok(self.u.run("remove", "hello"))
        self.assertTrue(os.path.exists(os.path.join(self.u.persist, "data/hello.properties")))
        r = self.ok(self.u.run("remove", "other", "--purge-data"))
        self.assertFalse(os.path.exists(os.path.join(self.u.persist, "data/other.properties")))
        self.assertTrue(os.path.exists(os.path.join(self.u.persist, "data/hello.properties")))
        self.assertIn("cleared the data of other", r.stdout.decode("utf-8"))

    def test_reports_overrides_still_in_the_ledger(self):
        self.installed("hello")
        line = "parking.soundDirections=hello|PERSISTENT|F1R1L1X1|F1R0L1X1|R"
        self.ledger(line + "\n")
        r = self.ok(self.u.run("remove", "hello"))
        err = r.stderr.decode("utf-8")
        self.assertIn("not restored yet", err)
        self.assertIn(line, err)

    def test_a_rerun_after_the_reload_request_failed_finishes_the_removal(self):
        # M2: jar and grants are gone when the marker push fails; the re-run must still request the reload
        self.installed("hello")
        marker = os.path.join(self.u.sdk, "plugins/hello.reload")
        before = read(marker)
        r = self.u.run("remove", "hello", fail_mv="hello.reload")
        self.assertNotEqual(0, r.returncode)
        self.assertFalse(os.path.exists(os.path.join(self.u.sdk, "plugins/hello.jar")))
        self.assertNotIn("hello=", self.grants())
        self.assertEqual(before, read(marker))

        r = self.ok(self.u.run("remove", "hello"))
        self.assertNotEqual(before, read(marker))
        self.assertIn("removed hello", r.stdout.decode("utf-8"))

    def test_a_plugin_known_only_by_its_ledger_lines_is_removed(self):
        # M2: no jar, grants line or marker, but the ledger still holds its overrides
        line = "parking.soundDirections=hello|PERSISTENT|F1R1L1X1|F1R0L1X1|R"
        self.ledger(line + "\n")
        r = self.ok(self.u.run("remove", "hello"))
        self.assertTrue(read(os.path.join(self.u.sdk, "plugins/hello.reload")).strip())
        self.assertIn(line, r.stderr.decode("utf-8"))

    def test_refuses_an_unknown_plugin(self):
        r = self.u.run("remove", "ghost")
        self.assertNotEqual(0, r.returncode)
        self.assertIn(b"is not installed", r.stderr)


if __name__ == "__main__":
    unittest.main()
