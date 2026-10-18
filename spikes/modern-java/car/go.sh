#!/bin/bash
# Throwaway spike: push payload to the bench MHI2Q, run on-unit.sh, save the log locally.
set -euo pipefail
HOST=${1:-10.20.0.23}
C=$(cd "$(dirname "$0")" && pwd)
SSHO=(-o ConnectTimeout=8 -o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa)
LOG=$C/results-$(date +%Y%m%d-%H%M%S).log
ssh "${SSHO[@]}" root@$HOST 'mkdir -p /ramdisk/mibsdk-spike'
# old OpenSSH on the unit: stream files over ssh instead of relying on scp/sftp
for f in "$C"/payload/*.jar "$C/on-unit.sh"; do
  ssh "${SSHO[@]}" root@$HOST "cat > /ramdisk/mibsdk-spike/$(basename "$f")" < "$f"
done
ssh "${SSHO[@]}" root@$HOST 'ksh /ramdisk/mibsdk-spike/on-unit.sh' 2>&1 | tee "$LOG"
echo "saved $LOG"
