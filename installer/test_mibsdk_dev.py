"""Drives installer/mibsdk-dev against a fake unit: a directory tree standing in for the head unit's /tmp, reached
through a fake `ssh` on PATH that runs each remote command locally with /tmp rewritten into that tree. A fake
/tmp/mibpkg records its argv; mibsdk-dev is only a transport, so these tests check what it uploads and which mibpkg
verbs it calls (mibpkg's own tests cover what those verbs do on the unit). Nothing ever touches a real device."""
import os
import re
import shutil
import stat
import subprocess
import tarfile
import tempfile
import unittest
import io
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "mibsdk-dev")
SEP = "\x1f"

FAKE_SSH = r"""#!/usr/bin/env bash
# Fake ssh for the fake unit: drops the options, records the host, runs the command locally under $FAKE_UNIT.
while [ $# -gt 0 ]; do
  case "$1" in
    -o) shift 2 ;;
    *@*) printf '%s\n' "${1#*@}" >>"$FAKE_UNIT/ssh.hosts"; shift; break ;;
    *) shift ;;
  esac
done
cmd="$*"
# Test hook: a file that changes on disk once the tool has connected (after it took its snapshot).
if [ -n "$FAKE_REWRITE" ] && [ "$cmd" = "echo ok" ]; then printf tampered >"$FAKE_REWRITE"; fi
cmd="${cmd//\/tmp\//$FAKE_UNIT/tmp/}"
# Test hook: an upload whose target contains $FAKE_CORRUPT_UPLOAD arrives damaged (a cut link): one extra byte lands.
if [ -n "$FAKE_CORRUPT_UPLOAD" ]; then
  case "$cmd" in "cat > "*"$FAKE_CORRUPT_UPLOAD"*) cmd="{ cat; printf X; } ${cmd#cat }" ;; esac
fi
# Like OpenSSH, read all of our stdin (it would forward it to the remote command): a caller's `while read` loop loses the rest.
cat | sh -c "$cmd"
exit ${PIPESTATUS[1]}
"""

# The unit's mibpkg: records argv (fields separated by \x1f, one call per line) and exits $FAKE_MIBPKG_RC after
# printing $FAKE_MIBPKG_OUT; `sdk status` answers with $FAKE_STATUS_OUT (stdout), $FAKE_STATUS_ERR (stderr) and
# $FAKE_STATUS_RC instead.
FAKE_MIBPKG = r"""#!/bin/sh
for a in "$@"; do printf '%s\037' "$a"; done >>"$FAKE_UNIT/mibpkg.argv"
printf '\n' >>"$FAKE_UNIT/mibpkg.argv"
if [ "$1" = sdk ] && [ "$2" = status ]; then
  [ -z "$FAKE_STATUS_OUT" ] || printf '%s\n' "$FAKE_STATUS_OUT"
  [ -z "$FAKE_STATUS_ERR" ] || printf '%s\n' "$FAKE_STATUS_ERR" >&2
  exit "${FAKE_STATUS_RC:-0}"
fi
[ -z "$FAKE_MIBPKG_OUT" ] || printf '%s\n' "$FAKE_MIBPKG_OUT"
exit "${FAKE_MIBPKG_RC:-0}"
"""

FAKE_NOOP = "#!/bin/sh\nexit 0\n"

PLUGIN_MANIFEST = """[package]
name = "%s"
version = "1.0.0"
description = "a plugin"

[plugin]
api = 2
"""


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


def mpk(path, name, extra=b""):
    """A gzip'd tar with manifest.toml first, like mibpkg build's output."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with tarfile.open(path, "w:gz") as t:
        for n, data in (("manifest.toml", (PLUGIN_MANIFEST % name).encode("utf-8")), (name + ".jar", b"jar" + extra)):
            info = tarfile.TarInfo(n)
            info.size = len(data)
            t.addfile(info, io.BytesIO(data))
    return path


class FakeUnit(object):
    def __init__(self, root):
        self.root = root
        self.unit = os.path.join(root, "unit")
        self.tmp = os.path.join(self.unit, "tmp")
        os.makedirs(self.tmp)
        bindir = os.path.join(root, "bin")
        executable(os.path.join(bindir, "ssh"), FAKE_SSH)
        executable(os.path.join(bindir, "slay"), FAKE_NOOP)
        executable(os.path.join(bindir, "sync"), FAKE_NOOP)
        executable(self.mibpkg(), FAKE_MIBPKG)
        self.dist = os.path.join(root, "dist")
        os.makedirs(self.dist)
        self.env = dict(os.environ)
        self.env["PATH"] = bindir + os.pathsep + self.env.get("PATH", "")
        self.env["FAKE_UNIT"] = self.unit
        self.env["MIBSDK_DIST"] = self.dist
        self.env["TMPDIR"] = root
        self.env.pop("MIBSDK_MPK", None)

    def mibpkg(self):
        return os.path.join(self.tmp, "mibpkg")

    def calls(self):
        """mibpkg's argv lists, in call order."""
        path = os.path.join(self.unit, "mibpkg.argv")
        if not os.path.exists(path):
            return []
        return [line.split(SEP)[:-1] for line in read(path).decode("utf-8").splitlines()]

    def uploads(self):
        return sorted(n for n in os.listdir(self.tmp) if n.startswith("mibpkg.") and n != "mibpkg")

    def hosts(self):
        path = os.path.join(self.unit, "ssh.hosts")
        return set(read(path).decode().split()) if os.path.exists(path) else set()

    def run(self, *args, **kw):
        env = dict(self.env)
        env["FAKE_REWRITE"] = kw.get("rewrite", "")
        env["FAKE_CORRUPT_UPLOAD"] = kw.get("corrupt", "")
        env["FAKE_MIBPKG_RC"] = str(kw.get("rc", 0))
        env["FAKE_MIBPKG_OUT"] = kw.get("out", "")
        env["FAKE_STATUS_RC"] = str(kw.get("status_rc", 0))
        env["FAKE_STATUS_OUT"] = kw.get("status_out", "")
        env["FAKE_STATUS_ERR"] = kw.get("status_err", "")
        env.update(kw.get("env", {}))
        return subprocess.run([TOOL] + list(args), env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=60)


class FakeUnitTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="mibsdk-dev-test.")
        self.u = FakeUnit(self.tmp)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def ok(self, result):
        self.assertEqual(0, result.returncode, result.stderr.decode("utf-8", "replace"))
        return result

    def failed(self, result, rc=None):
        if rc is None:
            self.assertNotEqual(0, result.returncode, result.stdout)
        else:
            self.assertEqual(rc, result.returncode, result.stderr.decode("utf-8", "replace"))
        return result.stderr.decode("utf-8", "replace")

    def sdk_mpk(self, version="0.1.0"):
        return mpk(os.path.join(self.u.dist, "mibsdk-%s.mpk" % version), "mibsdk", version.encode())

    def plugin_jar(self, plugin_id, stamped=True):
        path = os.path.join(self.tmp, "plugins", plugin_id + ".jar")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("plugin.json", '{"id": "%s"}' % plugin_id)
        if stamped:
            write(path + ".stamp", b"format=1\nkind=plugin\nplugin.id=" + plugin_id.encode() + b"\n")
        return path

    def upload_id(self, call):
        """The upload id of an install-file call, checked against mibpkg's rule (32 lowercase hex)."""
        self.assertEqual("install-file", call[0])
        self.assertRegex(call[2], r"^[0-9a-f]{32}$")
        return call[2]


class InstallTest(FakeUnitTestCase):
    def test_install_uploads_the_single_mpk_and_calls_install_file(self):
        src = self.sdk_mpk()
        self.ok(self.u.run("install"))
        calls = self.u.calls()
        self.assertEqual(["sdk", "status"], calls[0], "probe first")
        uid = self.upload_id(calls[1])
        self.assertEqual(["install-file", "mibsdk", uid, "-"], calls[1])
        self.assertEqual(2, len(calls))
        self.assertEqual(read(src), read(os.path.join(self.u.tmp, "mibpkg.upload.%s.mpk" % uid)))

    def test_install_refuses_zero_or_two_mpks(self):
        err = self.failed(self.u.run("install"))
        self.assertIn("found 0 mibsdk-*.mpk in %s; build with ./gradlew :dist:mibpkgPackage or set MIBSDK_MPK"
                      % self.u.dist, err)
        self.sdk_mpk("0.1.0")
        self.sdk_mpk("0.2.0")
        err = self.failed(self.u.run("install"))
        self.assertIn("found 2 mibsdk-*.mpk in %s" % self.u.dist, err)
        self.assertEqual([], self.u.uploads())
        self.assertEqual([], [c for c in self.u.calls() if c[0] != "sdk"], "nothing installed")

    def test_install_uses_MIBSDK_MPK(self):
        self.sdk_mpk("0.1.0")
        self.sdk_mpk("0.2.0")
        chosen = mpk(os.path.join(self.tmp, "elsewhere", "mibsdk-9.9.9.mpk"), "mibsdk", b"chosen")
        self.ok(self.u.run("install", env={"MIBSDK_MPK": chosen}))
        uid = self.upload_id(self.u.calls()[1])
        self.assertEqual(read(chosen), read(os.path.join(self.u.tmp, "mibpkg.upload.%s.mpk" % uid)))
        err = self.failed(self.u.run("install", env={"MIBSDK_MPK": os.path.join(self.tmp, "missing.mpk")}))
        self.assertIn("missing.mpk", err)

    def test_pushes_the_bytes_it_verified_not_a_jar_rewritten_afterwards(self):
        src = self.sdk_mpk()
        original = read(src)
        self.ok(self.u.run("install", rewrite=src))
        self.assertEqual(b"tampered", read(src))
        uid = self.upload_id(self.u.calls()[1])
        self.assertEqual(original, read(os.path.join(self.u.tmp, "mibpkg.upload.%s.mpk" % uid)))

    def test_a_damaged_upload_is_removed_and_nothing_is_installed(self):
        self.sdk_mpk()
        err = self.failed(self.u.run("install", corrupt="mibpkg.upload."))
        self.assertIn("upload", err)
        self.assertEqual([], self.u.uploads())
        self.assertEqual([["sdk", "status"]], self.u.calls())

    def test_each_install_uploads_afresh_under_a_new_id(self):
        self.sdk_mpk()
        self.ok(self.u.run("install"))
        self.ok(self.u.run("install"))
        ids = [c[2] for c in self.u.calls() if c[0] == "install-file"]
        self.assertEqual(2, len(ids))
        self.assertNotEqual(ids[0], ids[1])

    def test_install_file_failure_passes_its_exit_code_and_output_through(self):
        self.sdk_mpk()
        r = self.u.run("install", rc=1, out="mibpkg: unsupported HMI")
        self.failed(r, 1)
        self.assertIn(b"mibpkg: unsupported HMI", r.stdout)


class ProbeTest(FakeUnitTestCase):
    def test_old_or_missing_mibpkg_is_refused_before_any_upload(self):
        self.sdk_mpk()
        jar = self.plugin_jar("hello")
        for args in (("install",), ("push", jar), ("grant", "hello", "network"), ("status",)):
            err = self.failed(self.u.run(*args, status_rc=1, status_err='mibpkg: unknown action "sdk"'))
            self.assertIn("mibpkg on 10.20.0.23 is missing or older than 0.8.0; update it (see the mibpkg README)", err)
        self.assertEqual([], self.u.uploads())
        self.assertTrue(all(c == ["sdk", "status"] for c in self.u.calls()))
        os.remove(self.u.mibpkg())
        err = self.failed(self.u.run("install"))
        self.assertIn("is missing or older than 0.8.0", err)
        self.assertEqual([], self.u.uploads())

    def test_other_sdk_status_failures_are_not_called_an_old_mibpkg(self):
        self.sdk_mpk()
        for args in (("install",), ("status",)):
            err = self.failed(self.u.run(*args, status_rc=1, status_err="mibpkg: reading /tmp/mibpkg.status: I/O error"))
            self.assertNotIn("older than 0.8.0", err)
            self.assertIn("mibpkg sdk status failed on 10.20.0.23 (exit 1)", err)
            self.assertIn("I/O error", err)
        self.assertEqual([], self.u.uploads())

    def test_log_and_restart_do_not_probe(self):
        write(os.path.join(self.u.tmp, "mibsdk.log"), b"1 STATUS hello ENABLED ok\n")
        r = self.ok(self.u.run("log"))
        self.assertEqual(b"1 STATUS hello ENABLED ok\n", r.stdout)
        self.ok(self.u.run("restart"))
        self.assertEqual([], self.u.calls())

    def test_host_is_the_last_positional_argument(self):
        self.ok(self.u.run("grant", "hello", "network", "10.1.2.3"))
        self.assertEqual({"10.1.2.3"}, self.u.hosts())
        self.assertIn("too many arguments", self.failed(self.u.run("grant", "hello", "network", "h", "extra")))
        self.assertIn("invalid host", self.failed(self.u.run("safe-mode", "on", "-oProxyCommand=x")))


class InstallPluginTest(FakeUnitTestCase):
    def test_install_plugin_passes_approve_and_exit_3_through(self):
        src = mpk(os.path.join(self.tmp, "out", "hello-menu-1.0.0.mpk"), "hello-menu")
        r = self.u.run("install-plugin", src, rc=3, out="network  Internet access\napprove with: --approve=network")
        self.failed(r, 3)
        self.assertIn(b"approve with: --approve=network", r.stdout)
        uid = self.upload_id(self.u.calls()[1])
        self.assertEqual(["install-file", "hello-menu", uid, "-"], self.u.calls()[1])
        self.assertEqual(read(src), read(os.path.join(self.u.tmp, "mibpkg.upload.%s.mpk" % uid)))

        self.ok(self.u.run("install-plugin", src, "--approve=network,ui.slot.carmenu"))
        last = self.u.calls()[-1]
        self.assertEqual(["install-file", "hello-menu", self.upload_id(last), "-", "--approve=network,ui.slot.carmenu"],
                         last)
        self.ok(self.u.run("install-plugin", src, "--approve="))
        self.assertEqual("--approve=", self.u.calls()[-1][-1], "an empty list approves nothing, explicitly")

    def test_install_plugin_refuses_a_file_without_a_package_name(self):
        bad = os.path.join(self.tmp, "bad.mpk")
        write(bad, b"not a tar")
        self.assertIn("cannot read the package name", self.failed(self.u.run("install-plugin", bad)))
        self.assertIn("invalid --approve", self.failed(self.u.run("install-plugin", bad, "--approve=a;b")))
        self.assertEqual([], self.u.uploads())


class PushTest(FakeUnitTestCase):
    def test_push_requires_stamp_beside_jar(self):
        jar = self.plugin_jar("hello", stamped=False)
        err = self.failed(self.u.run("push", jar))
        self.assertIn(jar + ".stamp", err)
        self.assertIn(":mibPluginJar", err)
        self.assertEqual([], self.u.uploads())
        self.assertEqual([], self.u.calls(), "refused before connecting")

    def test_push_uploads_jar_and_stamp_and_calls_sdk_push(self):
        jar = self.plugin_jar("hello")
        self.ok(self.u.run("push", jar))
        self.assertEqual([["sdk", "status"], ["sdk", "push", "hello"]], self.u.calls())
        self.assertEqual(read(jar), read(os.path.join(self.u.tmp, "mibpkg.push.hello.jar")))
        self.assertEqual(read(jar + ".stamp"), read(os.path.join(self.u.tmp, "mibpkg.push.hello.jar.stamp")))

    def test_pushes_the_bytes_it_verified_not_a_jar_rewritten_afterwards(self):
        jar = self.plugin_jar("hello")
        original = read(jar)
        self.ok(self.u.run("push", jar, rewrite=jar))
        self.assertEqual(b"tampered", read(jar))
        self.assertEqual(original, read(os.path.join(self.u.tmp, "mibpkg.push.hello.jar")))

    def test_a_failed_stamp_upload_removes_both_push_files(self):
        jar = self.plugin_jar("hello")
        err = self.failed(self.u.run("push", jar, corrupt="mibpkg.push.hello.jar.stamp"), 1)
        self.assertIn("did not verify", err)
        self.assertEqual([], self.u.uploads())
        self.assertEqual([["sdk", "status"]], self.u.calls())

    def test_push_refuses_a_jar_without_a_plugin_id(self):
        jar = os.path.join(self.tmp, "x.jar")
        with zipfile.ZipFile(jar, "w") as z:
            z.writestr("plugin.json", '{"id": "bad id"}')
        write(jar + ".stamp", b"format=1\n")
        self.assertIn("invalid plugin id", self.failed(self.u.run("push", jar)))
        self.assertEqual([], self.u.uploads())


class SdkVerbTest(FakeUnitTestCase):
    def test_remove_maps_to_sdk_remove_with_purge_data_alias(self):
        self.ok(self.u.run("remove", "hello"))
        self.ok(self.u.run("remove", "hello", "--purge-data"))
        self.ok(self.u.run("remove", "--purge", "hello", "10.1.2.3"))
        removes = [c for c in self.u.calls() if c[:2] == ["sdk", "remove"]]
        self.assertEqual([["sdk", "remove", "hello"], ["sdk", "remove", "hello", "--purge"],
                          ["sdk", "remove", "hello", "--purge"]], removes)
        self.assertIn("invalid plugin id", self.failed(self.u.run("remove", "../x")))

    def test_uninstall_flags_pass_through(self):
        self.ok(self.u.run("uninstall"))
        self.ok(self.u.run("uninstall", "--purge", "--force"))
        self.ok(self.u.run("uninstall", "--force", "10.1.2.3"))
        got = [c for c in self.u.calls() if c[0] == "remove"]
        self.assertEqual([["remove", "mibsdk"], ["remove", "mibsdk", "--purge", "--force"], ["remove", "mibsdk", "--force"]],
                         got)
        self.assertEqual({"10.20.0.23", "10.1.2.3"}, self.u.hosts())
        r = self.u.run("uninstall", rc=1, out="refusing: overrides still in force")
        self.failed(r, 1)
        self.assertIn(b"refusing: overrides still in force", r.stdout)

    def test_grant_safe_mode_boot_state_data_clear_map_to_sdk_verbs(self):
        for args in (("grant", "hello", "network,vehicle.read"), ("grant", "hello", ""), ("safe-mode", "on"),
                     ("safe-mode", "off"), ("boot-state", "set-wrap", "3"), ("data-clear", "crashy")):
            self.ok(self.u.run(*args))
        verbs = [c for c in self.u.calls() if c != ["sdk", "status"]]
        self.assertEqual([["sdk", "grant", "hello", "network,vehicle.read"], ["sdk", "grant", "hello", ""],
                          ["sdk", "safe-mode", "on"], ["sdk", "safe-mode", "off"], ["sdk", "boot-state", "set-wrap", "3"],
                          ["sdk", "data-clear", "crashy"]], verbs)
        # mibpkg's exit codes pass through: 2 bad usage, 1 busy.
        self.failed(self.u.run("boot-state", "set-wrap", "x", rc=2), 2)
        self.failed(self.u.run("grant", "hello", "network", rc=1), 1)
        self.assertIn("invalid permission list", self.failed(self.u.run("grant", "hello", "a b")))

    def test_boot_state_without_a_count_is_a_usage_error(self):
        for args in (("boot-state", "set-wrap"), ("boot-state", "set-wrap", "x"), ("boot-state",)):
            err = self.failed(self.u.run(*args), 2)
            self.assertIn("usage: mibsdk-dev boot-state set-wrap <n>", err)
            self.assertNotIn("unbound", err)
        self.assertEqual([], self.u.calls())

    def test_host_side_usage_errors_exit_2_and_failures_exit_1(self):
        jar = self.plugin_jar("hello", stamped=False)
        for args in (("frobnicate",), ("remove", "../x"), ("data-clear", "a b"), ("remove", "hello", "--bogus"),
                     ("uninstall", "--purge-data"), ("grant", "hello", "a b"), ("grant", "hello"),
                     ("install-plugin", "x.mpk", "--approve=a;b"), ("safe-mode", "maybe"), ("push",),
                     ("grant", "hello", "network", "h", "extra"), ("safe-mode", "on", "-oProxyCommand=x")):
            self.failed(self.u.run(*args), 2)
        self.failed(self.u.run("install"), 1)       # no .mpk: a failure, not a usage error
        self.failed(self.u.run("push", jar), 1)     # no stamp
        self.failed(self.u.run("push", os.path.join(self.tmp, "nope.jar")), 1)
        self.assertEqual([], self.u.calls())

    def test_status_is_mibpkg_sdk_status_alone(self):
        write(os.path.join(self.u.tmp, "mibsdk.log"), b"1 STATUS hello ENABLED ok\n")
        r = self.ok(self.u.run("status", status_out="HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator\nsdk: 0.1.0"))
        self.assertEqual(b"HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator\nsdk: 0.1.0\n", r.stdout)
        self.assertEqual([["sdk", "status"]], self.u.calls())

    def test_script_has_no_remote_mount_calls(self):
        text = read(TOOL).decode("utf-8")
        self.assertNotIn("mount -u", text)
        self.assertIsNone(re.search(r"python3 .*(STAMP|GRANTS|LEDGER)_PY", text))


if __name__ == "__main__":
    unittest.main()
