#!/bin/ksh
# Throwaway spike, runs ON the MHI2Q. Starts short-lived, separate j9 processes (never touches the HMI's j9).
# Kills only by PID: `slay j9` would take down the HMI.
D=/ramdisk/mibsdk-spike
JAVA_HOME=/ifs/jre
# The unit's /ifs/jre/bin has the J9 libs but no j9 launcher, so go.sh stages the launcher (from the same
# firmware's ifs_hmi) in $D/jre/bin with symlinks to the unit's own libs.
export LD_LIBRARY_PATH=/proc/boot:$D/jre/bin:$JAVA_HOME/bin:/mnt/app/eso/hmi/lsd:/mnt/app/eso/lib
J9="$D/jre/bin/j9"
OPTS="-Xmx16m -Xquickstart -Dcom.ibm.oti.vm.bootstrap.library.path=$JAVA_HOME/bin"

run() {   # run <label> <args...>   (30 s cap)
  echo "=== $1"; shift
  $J9 $OPTS "$@" > $D/run.out 2>&1 &
  pid=$!
  ( sleep 30; kill -9 $pid 2>/dev/null && echo "TIMEOUT (killed $pid)" ) &
  guard=$!
  wait $pid; rc=$?
  kill $guard 2>/dev/null
  cat $D/run.out; echo "--- exit=$rc"
}

echo "@@SPIKE-START@@"
pidin -p j9 2>/dev/null | head -3            # HMI j9 before (should be unchanged after)

# which launch form gives us the JCL from lsd.jxe
run "v48 via -cp"          -Xbootclasspath:/ifs/lsd.jxe -cp $D/v1.4.jar vtest.V 48
run "v48 via bootcp"       -Xbootclasspath:/ifs/lsd.jxe:$D/v1.4.jar vtest.V 48
# class-file versions J9 2.3 accepts (verified and -noverify, which is how the HMI runs)
for v in 1.5 1.6; do
  run "v$v verified"  -Xbootclasspath:/ifs/lsd.jxe -cp $D/v$v.jar vtest.V $v
  run "v$v noverify"  -noverify -Xbootclasspath:/ifs/lsd.jxe -cp $D/v$v.jar vtest.V $v
done
# the backported modern-Java plugin, verified then -noverify
run "hello verified"  -Xbootclasspath:/ifs/lsd.jxe -cp $D/hello.jar spike.Hello
run "hello noverify"  -noverify -Xbootclasspath:/ifs/lsd.jxe -cp $D/hello.jar spike.Hello
# the backported Kotlin plugin (ProGuard-shrunk stdlib inside)
run "kotlin verified"  -Xbootclasspath:/ifs/lsd.jxe -cp $D/hellok.jar spikek.HelloK
run "kotlin noverify"  -noverify -Xbootclasspath:/ifs/lsd.jxe -cp $D/hellok.jar spikek.HelloK
# per-plugin class loader + reload
run "loader"  -noverify -Xbootclasspath:/ifs/lsd.jxe -cp $D/loader.jar loader.Loader $D/hello.jar spike.Hello
run "loader kotlin"  -noverify -Xbootclasspath:/ifs/lsd.jxe -cp $D/loader.jar loader.Loader $D/hellok.jar spikek.HelloK
pidin -p j9 2>/dev/null | head -3            # HMI j9 after
echo "@@SPIKE-END@@"
