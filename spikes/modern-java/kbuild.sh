#!/bin/bash
# Throwaway spike: Kotlin -> 1.8 (kotlinc, class lambdas) -> shrink with stdlib (ProGuard) -> v49 (Retrolambda)
# -> v48 (Retrotranslator + SDK compat shims) -> link-check against the car's JCL.
set -euo pipefail
S=$(cd "$(dirname "$0")" && pwd)
T=$S/tools
JDK17=/Library/Java/JavaVirtualMachines/jdk-17.0.5.jdk/Contents/Home
JDK8=$HOME/Library/Java/JavaVirtualMachines/corretto-1.8.0_372/Contents/Home
OUT=$S/out-k
rm -rf "$OUT"; mkdir -p "$OUT"/{k8,k5,k4}
cd "$S"

JAVA_HOME=$JDK17 "$T/kotlinc/bin/kotlinc" ksrc/spikek/HelloK.kt -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class \
  -Xstring-concat=inline -Xno-param-assertions -Xno-call-assertions -Xno-receiver-assertions -d "$OUT/k8.jar"

# shrink plugin + stdlib together; renaming pass is on (but keeps all names) only so ProGuard drops
# LocalVariableTable, whose Kotlin names ($i$a$-filter-...) are illegal in v48 class files
sed -e "s#out/k8.jar#$OUT/k8.jar#; s#out/k8-shrunk.jar#$OUT/k8-shrunk.jar#; s#injars tools/#injars $T/#" kotlin.pro > "$OUT/kotlin.pro"
"$JDK8/bin/java" -cp "$T/proguard-base-7.6.0.jar:$T/proguard-core-9.1.6.jar:$T/log4j-api-2.23.1.jar:$T/log4j-core-2.23.1.jar:$T/gson-2.11.0.jar:$T/kotlinc/lib/kotlin-stdlib.jar" \
  proguard.ProGuard @"$OUT/kotlin.pro" > "$OUT/proguard.log" 2>&1
(cd "$OUT/k8" && unzip -q ../k8-shrunk.jar)

"$JDK8/bin/java" -Dretrolambda.inputDir="$OUT/k8" -Dretrolambda.outputDir="$OUT/k5" \
  -Dretrolambda.classpath="$OUT/k8:$JDK8/jre/lib/rt.jar" -Dretrolambda.bytecodeVersion=49 \
  -Dretrolambda.defaultMethods=true -jar "$T/retrolambda-2.5.7.jar" > "$OUT/retrolambda.log" 2>&1

"$JDK8/bin/java" -cp "$T/retrotranslator-transformer-1.2.9.jar:$T/retrotranslator-runtime-1.2.9.jar:$S/compat/out" \
  net.sf.retrotranslator.transformer.Retrotranslator \
  -srcdir "$OUT/k5" -destdir "$OUT/k4" -target 1.4 -embed spikek.rt -stripsign \
  -backport "java.util.function:mibsdk.compat.java.util.function;java.util:mibsdk.compat.java.util;java.lang:mibsdk.compat.java.lang"
cp -R "$S/compat/out/" "$OUT/k4/"

"$JDK17/bin/java" --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED "$S/LinkCheck.java" "$OUT/k4" "$S/carjcl" \
  | tee "$OUT/linkcheck.log" || true
(cd "$OUT/k4" && "$JDK8/bin/jar" cf "$OUT/hellok.jar" .)
echo "built $OUT/hellok.jar"
