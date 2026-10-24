#!/usr/bin/env bash
# run.sh install|uninstall|log [host]: put the Plan 2 UI spike (mibsdk-spike-ui.jar) on a unit that already runs the
# SDK, by pointing the installed bundles file's HMICar= line at mibsdk.spike.ui.SpikeCarActivator.
# Self-contained (it sources nothing; the push conventions are those of the pre-mibpkg installer/mibsdk-dev):
# push via `cat >` to a temp file, verify byte for byte, rename; /mnt/app read-write only between rw_begin/rw_end.
# Never reads or prints the j9 command line.
set -euo pipefail

HOST="${2:-10.20.0.23}"
LSD="/mnt/app/eso/hmi/lsd"
BUNDLES="$LSD/bundles_dsitracer_enabled.properties"
STOCK_BUNDLES="$LSD/mibsdk/stock/bundles_dsitracer_enabled.properties"
JAR_NAME="mibsdk-spike-ui.jar"
JAR_DST="$LSD/jars/$JAR_NAME"
STOCK_LINE="HMICar=de.audi.tghu.car.hmi.Activator"
SPIKE_LINE="HMICar=mibsdk.spike.ui.SpikeCarActivator"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_SRC="$HERE/build/libs/$JAR_NAME"
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

# set_hmicar <from line> <to line>: rewrite exactly one HMICar= line of the installed bundles file (mode kept).
set_hmicar() {
  local from="$1" to="$2"
  fetch "$BUNDLES" "$TMP/cur" || die "cannot read $BUNDLES"
  # The stock file has CRLF line ends: compare without the CR, keep it on the rewritten line.
  python3 -I - "$TMP/cur" "$TMP/new" "$from" "$to" <<'PY' || die "HMICar= line of $BUNDLES is not '$from'; refusing"
import sys
src, dst, frm, to = sys.argv[1:5]
lines = open(src, "rb").read().split(b"\n")
hits = [i for i, l in enumerate(lines) if l.startswith(b"HMICar=")]
if len(hits) != 1 or lines[hits[0]].rstrip(b"\r") != frm.encode():
    sys.exit(1)
l = lines[hits[0]]
lines[hits[0]] = to.encode() + (b"\r" if l.endswith(b"\r") else b"")
open(dst, "wb").write(b"\n".join(lines))
PY
  local mode; mode="$(rsh "ls -ld '$BUNDLES'")"; mode="${mode%% *}"
  [ "$mode" = "-rwxrwxrwx" ] || die "unexpected mode $mode on $BUNDLES"
  push_file "$TMP/new" "$BUNDLES"
  rsh "chmod 777 '$BUNDLES'" || die "cannot chmod $BUNDLES"
}

case "${1:-}" in
  install)
    [ -f "$JAR_SRC" ] || die "$JAR_SRC missing; build: ./gradlew :spikes:hmi-ui:carJar"
    rsh "[ -e '$STOCK_BUNDLES' ]" || die "the SDK is not installed (no $STOCK_BUNDLES)"
    fetch "$STOCK_BUNDLES" "$TMP/stock"
    tr -d '\r' <"$TMP/stock" | grep -qx "$STOCK_LINE" || die "stock backup has no '$STOCK_LINE'"
    rw_begin
    rsh "rm -f '$LSD/jars/.push.tmp' '$LSD/.push.tmp'"
    push_file "$JAR_SRC" "$JAR_DST"
    set_hmicar "$STOCK_LINE" "$SPIKE_LINE"
    rw_end
    echo "spike installed; restart: installer/mibsdk-dev restart"
    ;;
  uninstall)
    fetch "$STOCK_BUNDLES" "$TMP/stock" || die "cannot read $STOCK_BUNDLES"
    line="$(tr -d '\r' <"$TMP/stock" | grep '^HMICar=')"
    [ "$line" = "$STOCK_LINE" ] || die "unexpected stock HMICar line '$line'"
    rw_begin
    set_hmicar "$SPIKE_LINE" "$line"
    rsh "rm -f '$JAR_DST'" || die "cannot remove $JAR_DST"
    rw_end
    echo "spike removed; restart: installer/mibsdk-dev restart"
    ;;
  log)
    fetch /tmp/mibsdk.log "$TMP/log" || die "/tmp/mibsdk.log not found"
    cat "$TMP/log"
    ;;
  *) echo "usage: run.sh install|uninstall|log [host]" >&2; exit 2 ;;
esac
