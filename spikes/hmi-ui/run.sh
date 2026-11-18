#!/usr/bin/env bash
# run.sh install [--car-only] | add-system | uninstall | status | log   [host]
#
# Puts the UI spike (mibsdk-spike-ui.jar, spikes A/B/C) on a unit that runs the SDK installed by mibpkg, by pointing
# the bundles file's HMICar= line at mibsdk.spike.ui.SpikeCarActivator and (unless --car-only) its HMISystem= line at
# mibsdk.spike.ui.SpikeSystemActivator.
#
#   install [--car-only]  save a byte copy of the live bundles file to /mnt/persist/mibsdk-spike/bundles.before-d,
#                         push the jar and the icons (lsd/mibsdk-spike/icons/), rewrite the line(s). --car-only
#                         rewrites HMICar= only and keeps HMISystem= byte for byte, whatever it is (stock, or the
#                         SDK's SdkSystemActivator since the main-menu takeover); without it HMISystem= must be stock
#   add-system            spike already installed --car-only: rewrite HMISystem= too; bundles.before is kept
#                         (it stays the pre-spike copy, so uninstall goes all the way back)
#   push-jar              spike installed: replace the jar only (takes effect at the next restart)
#   uninstall             write bundles.before-d back (host cmp before and after), remove the jar and the icons dir;
#                         refuses unless the live file, with the spike line(s) put back, equals the backup byte for
#                         byte (so nothing else changed while the spike was installed)
#   status                the two lines, jar/icons/backup presence, /mnt/app mount flags
#
# Self-contained (sources nothing): push via `cat >` to a temp file, verify byte for byte, rename. /mnt/app is
# read-write only between rw_begin/rw_end, and every remote command in that section re-asserts `mount -uw` (F8).
# Never reads or prints the j9 command line. Do not run mibpkg install/push/grant/remove while the spike is installed.
set -euo pipefail

CAR_ONLY=0
ARGS=()
for a in "$@"; do
  case "$a" in
    --car-only) CAR_ONLY=1 ;;
    *) ARGS+=("$a") ;;
  esac
done
CMD="${ARGS[0]:-}"
HOST="${ARGS[1]:-10.20.0.23}"

LSD="/mnt/app/eso/hmi/lsd"
BUNDLES="$LSD/bundles_dsitracer_enabled.properties"
PERSIST="/mnt/persist/mibsdk-spike"
# Spike D: its own name, so the spike C copy (bundles.before, a pre-takeover SDK state) is never reused.
BACKUP="$PERSIST/bundles.before-d"
JAR_NAME="mibsdk-spike-ui.jar"
JAR_DST="$LSD/jars/$JAR_NAME"
ICONS_DST="$LSD/mibsdk-spike/icons"
SDK_CAR_LINE="HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator"
SPIKE_CAR_LINE="HMICar=mibsdk.spike.ui.SpikeCarActivator"
STOCK_SYS_LINE="HMISystem=de.audi.tghu.system.hmi.Activator"
SPIKE_SYS_LINE="HMISystem=mibsdk.spike.ui.SpikeSystemActivator"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_SRC="$HERE/build/libs/$JAR_NAME"
ICONS_SRC="$HERE/icons"
ICONS=(609100 609101 609102 609103 609110 609111 609120 p20 p30)
SSH_OPTS=(-o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa -o BatchMode=yes
          -o ConnectTimeout=8 -o ServerAliveInterval=5 -o ServerAliveCountMax=3)

TMP="$(mktemp -d "${TMPDIR:-/tmp}/spike-ui.XXXXXX")"
RW=0
die() { echo "run.sh: $*" >&2; exit 1; }
cleanup() { local rc=$?; [ "$RW" = 1 ] && rw_end; rm -rf "$TMP"; return $rc; }
trap cleanup EXIT

rsh() {
  if [ "$RW" = 1 ]; then ssh "${SSH_OPTS[@]}" "root@$HOST" "mount -uw /mnt/app; $*" 2>/dev/null
  else ssh "${SSH_OPTS[@]}" "root@$HOST" "$@" 2>/dev/null; fi
}
fetch() { rsh "cat '$1'" >"$2"; }
rw_begin() { rsh "mount -uw /mnt/app" || die "cannot remount /mnt/app read-write"; RW=1; }
rw_end() { RW=0; rsh "sync; mount -ur /mnt/app" || echo "run.sh: WARNING: /mnt/app left read-write" >&2; }

push_file() {
  local src="$1" dst="$2" tmp="${2%/*}/.push.tmp"
  rsh "cat > '$tmp'" <"$src" || die "failed to write $dst"
  fetch "$tmp" "$TMP/verify" && cmp -s "$src" "$TMP/verify" || { rsh "rm -f '$tmp'"; die "verification of $dst failed"; }
  rsh "mv '$tmp' '$dst'; [ ! -e '$tmp' ]" && fetch "$dst" "$TMP/verify" && cmp -s "$src" "$TMP/verify" \
    || { rsh "rm -f '$tmp'"; die "failed to move $dst into place"; }
}

# line_of <file> <key>: the single "<key>=" line of a local copy, without CR (empty if absent or not unique).
line_of() {
  python3 -I - "$1" "$2" <<'PY'
import sys
lines = [l.rstrip(b"\r") for l in open(sys.argv[1], "rb").read().split(b"\n")]
hits = [l for l in lines if l.startswith(sys.argv[2].encode() + b"=")]
print(hits[0].decode() if len(hits) == 1 else "")
PY
}

# rewrite <in> <out> <from line> <to line>: replace exactly one line (key from <from>), keep its CR.
rewrite() {
  python3 -I - "$@" <<'PY'
import sys
src, dst, frm, to = sys.argv[1:5]
key = frm.split("=", 1)[0].encode() + b"="
lines = open(src, "rb").read().split(b"\n")
hits = [i for i, l in enumerate(lines) if l.startswith(key)]
if len(hits) != 1 or lines[hits[0]].rstrip(b"\r") != frm.encode():
    sys.exit(1)
l = lines[hits[0]]
lines[hits[0]] = to.encode() + (b"\r" if l.endswith(b"\r") else b"")
open(dst, "wb").write(b"\n".join(lines))
PY
}

check_mode() {
  local mode; mode="$(rsh "ls -ld '$BUNDLES'")"; mode="${mode%% *}"
  [ "$mode" = "-rwxrwxrwx" ] || die "unexpected mode $mode on $BUNDLES"
}

# Inside rw: push a new bundles file and keep mode 777.
push_bundles() {
  push_file "$1" "$BUNDLES"
  rsh "chmod 777 '$BUNDLES'" || die "cannot chmod $BUNDLES"
}

case "$CMD" in
  install)
    [ -f "$JAR_SRC" ] || die "$JAR_SRC missing; build: ./gradlew :spikes:hmi-ui:carJar"
    for i in "${ICONS[@]}"; do [ -f "$ICONS_SRC/$i.png" ] || die "$ICONS_SRC/$i.png missing; run make_icons.py"; done
    fetch "$BUNDLES" "$TMP/cur" || die "cannot read $BUNDLES"
    [ "$(line_of "$TMP/cur" HMICar)" = "$SDK_CAR_LINE" ] || die "live HMICar= is not '$SDK_CAR_LINE'; refusing"
    LIVE_SYS="$(line_of "$TMP/cur" HMISystem)"
    [ -n "$LIVE_SYS" ] || die "live bundles file has no single HMISystem= line; refusing"
    [ "$LIVE_SYS" != "$SPIKE_SYS_LINE" ] || die "live HMISystem= is already the spike line; refusing"
    if [ "$CAR_ONLY" != 1 ]; then
      [ "$LIVE_SYS" = "$STOCK_SYS_LINE" ] || die "live HMISystem= is not stock ('$LIVE_SYS'); use --car-only; refusing"
    fi
    check_mode
    # bundles.before: a byte copy of the live file. An existing copy must be identical (a stale one would undo
    # whatever happened since).
    if rsh "[ -e '$BACKUP' ]"; then
      fetch "$BACKUP" "$TMP/backup" && cmp -s "$TMP/cur" "$TMP/backup" \
        || die "$BACKUP exists and differs from the live file; resolve by hand"
      echo "bundles.before already present and identical"
    else
      rsh "mkdir -p '$PERSIST' && cat > '$BACKUP'" <"$TMP/cur" || die "cannot write $BACKUP"
      fetch "$BACKUP" "$TMP/backup" && cmp -s "$TMP/cur" "$TMP/backup" || die "verification of $BACKUP failed"
      echo "saved $BACKUP ($(wc -c <"$TMP/cur" | tr -d ' ') bytes)"
    fi
    rewrite "$TMP/cur" "$TMP/new1" "$SDK_CAR_LINE" "$SPIKE_CAR_LINE" || die "HMICar= rewrite failed"
    if [ "$CAR_ONLY" = 1 ]; then
      cp "$TMP/new1" "$TMP/new"
      [ "$(line_of "$TMP/new" HMISystem)" = "$LIVE_SYS" ] || die "HMISystem= changed in the rewrite; refusing"
    else
      rewrite "$TMP/new1" "$TMP/new" "$STOCK_SYS_LINE" "$SPIKE_SYS_LINE" || die "HMISystem= rewrite failed"
    fi
    rw_begin
    rsh "rm -f '$LSD/jars/.push.tmp' '$LSD/.push.tmp'; mkdir -p '$ICONS_DST'; rm -f '$ICONS_DST/.push.tmp'" \
      || die "cannot prepare $ICONS_DST"
    push_file "$JAR_SRC" "$JAR_DST"
    for i in "${ICONS[@]}"; do push_file "$ICONS_SRC/$i.png" "$ICONS_DST/$i.png"; done
    push_bundles "$TMP/new"
    rw_end
    echo "rewrote: $SDK_CAR_LINE -> $SPIKE_CAR_LINE"
    if [ "$CAR_ONLY" = 1 ]; then echo "kept:    $LIVE_SYS (--car-only, byte for byte)"
    else echo "rewrote: $STOCK_SYS_LINE -> $SPIKE_SYS_LINE"; fi
    echo "spike installed; restart: installer/mibsdk-dev restart"
    ;;
  add-system)
    rsh "[ -e '$BACKUP' ] && [ -e '$JAR_DST' ]" || die "spike not installed (no $BACKUP or $JAR_DST)"
    fetch "$BUNDLES" "$TMP/cur" || die "cannot read $BUNDLES"
    [ "$(line_of "$TMP/cur" HMICar)" = "$SPIKE_CAR_LINE" ] || die "live HMICar= is not the spike line; refusing"
    [ "$(line_of "$TMP/cur" HMISystem)" = "$STOCK_SYS_LINE" ] || die "live HMISystem= is not stock; refusing"
    fetch "$BACKUP" "$TMP/backup" || die "cannot read $BACKUP"
    [ "$(line_of "$TMP/backup" HMICar)" = "$SDK_CAR_LINE" ] || die "$BACKUP is not the SDK state; refusing"
    check_mode
    rewrite "$TMP/cur" "$TMP/new" "$STOCK_SYS_LINE" "$SPIKE_SYS_LINE" || die "HMISystem= rewrite failed"
    rw_begin
    push_bundles "$TMP/new"
    rw_end
    echo "rewrote: $STOCK_SYS_LINE -> $SPIKE_SYS_LINE (bundles.before kept); restart: installer/mibsdk-dev restart"
    ;;
  push-jar)
    [ -f "$JAR_SRC" ] || die "$JAR_SRC missing; build: ./gradlew :spikes:hmi-ui:carJar"
    rsh "[ -e '$BACKUP' ] && [ -e '$JAR_DST' ]" || die "spike not installed (no $BACKUP or $JAR_DST)"
    rw_begin
    push_file "$JAR_SRC" "$JAR_DST"
    rw_end
    echo "jar replaced (verified); takes effect at the next restart"
    ;;
  uninstall)
    fetch "$BACKUP" "$TMP/backup" || die "cannot read $BACKUP"
    [ "$(line_of "$TMP/backup" HMICar)" = "$SDK_CAR_LINE" ] || die "$BACKUP has no '$SDK_CAR_LINE'; refusing"
    BACKUP_SYS="$(line_of "$TMP/backup" HMISystem)"
    [ -n "$BACKUP_SYS" ] && [ "$BACKUP_SYS" != "$SPIKE_SYS_LINE" ] || die "$BACKUP has no usable HMISystem=; refusing"
    # The live file with the spike line(s) put back must be the backup byte for byte: nothing else may have changed.
    fetch "$BUNDLES" "$TMP/cur" || die "cannot read $BUNDLES"
    cp "$TMP/cur" "$TMP/undo"
    if [ "$(line_of "$TMP/undo" HMICar)" = "$SPIKE_CAR_LINE" ]; then
      rewrite "$TMP/undo" "$TMP/undo1" "$SPIKE_CAR_LINE" "$SDK_CAR_LINE" || die "HMICar= undo failed"
      mv "$TMP/undo1" "$TMP/undo"
    fi
    if [ "$(line_of "$TMP/undo" HMISystem)" = "$SPIKE_SYS_LINE" ]; then
      rewrite "$TMP/undo" "$TMP/undo1" "$SPIKE_SYS_LINE" "$BACKUP_SYS" || die "HMISystem= undo failed"
      mv "$TMP/undo1" "$TMP/undo"
    fi
    cmp -s "$TMP/undo" "$TMP/backup" || die "live file differs from $BACKUP beyond the spike lines; resolve by hand"
    rw_begin
    push_bundles "$TMP/backup"
    fetch "$BUNDLES" "$TMP/after" && cmp "$TMP/backup" "$TMP/after" || die "bundles file differs from $BACKUP after restore"
    # No rmdir on the unit: remove the spike's own fixed dir with rm -rf.
    rsh "rm -f '$JAR_DST'; rm -rf '$LSD/mibsdk-spike'" \
      || die "cannot remove $JAR_DST / $ICONS_DST"
    rw_end
    rsh "[ ! -e '$JAR_DST' ] && [ ! -e '$LSD/mibsdk-spike' ]" || die "jar or icons dir still present"
    echo "bundles file restored from $BACKUP (host cmp identical); jar and icons removed; restart: installer/mibsdk-dev restart"
    ;;
  status)
    fetch "$BUNDLES" "$TMP/cur" || die "cannot read $BUNDLES"
    echo "live:   $(line_of "$TMP/cur" HMICar)"
    echo "live:   $(line_of "$TMP/cur" HMISystem)"
    if rsh "[ -e '$BACKUP' ]"; then
      fetch "$BACKUP" "$TMP/backup"
      echo "backup: $BACKUP present, $(wc -c <"$TMP/backup" | tr -d ' ') bytes, $(cmp -s "$TMP/cur" "$TMP/backup" && echo identical to live || echo differs from live)"
    else
      echo "backup: none"
    fi
    rsh "[ -e '$JAR_DST' ]" && echo "jar:    present" || echo "jar:    absent"
    rsh "[ -d '$ICONS_DST' ]" && echo "icons:  present" || echo "icons:  absent"
    echo "mount:  $(rsh "mount" | grep ' on /mnt/app type' || true)"
    ;;
  log)
    fetch /tmp/mibsdk.log "$TMP/log" || die "/tmp/mibsdk.log not found"
    cat "$TMP/log"
    ;;
  *) echo "usage: run.sh install [--car-only] | add-system | push-jar | uninstall | status | log  [host]" >&2; exit 2 ;;
esac
