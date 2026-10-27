#!/bin/bash
# Throwaway spike: modern Java -> Java 8 bytecode (Jabel) -> Java 5 (Retrolambda) -> 1.4 (Retrotranslator),
# then verify every reference resolves against the car's own JCL (java.* extracted from lsd.jxe).
set -euo pipefail
S=$(cd "$(dirname "$0")" && pwd)
T=$S/tools
JDK17=/Library/Java/JavaVirtualMachines/jdk-17.0.5.jdk/Contents/Home
JDK8="${MIBSDK_JDK8_HOME:?set MIBSDK_JDK8_HOME to a JDK 8 home}"
OUT=$S/out
rm -rf "$OUT"; mkdir -p "$OUT"/{j8,j5,j4}

# 1. modern syntax -> Java 8 class files (v52)
"$JDK17/bin/javac" --release 8 \
  -processorpath "$T/jabel-javac-plugin-1.0.0.jar:$T/byte-buddy-1.12.19.jar:$T/byte-buddy-agent-1.12.19.jar" \
  -Xplugin:jabel -d "$OUT/j8" $(find "$S/src" -name '*.java')

# 2. lambdas / default methods / try-with-resources -> Java 5 class files (v49)
"$JDK8/bin/java" -Dretrolambda.inputDir="$OUT/j8" -Dretrolambda.outputDir="$OUT/j5" \
  -Dretrolambda.classpath="$OUT/j8:$JDK8/jre/lib/rt.jar" -Dretrolambda.bytecodeVersion=49 \
  -Dretrolambda.defaultMethods=true -jar "$T/retrolambda-2.5.7.jar"

# 3. Java 5 -> 1.4 class files (v48). Missing JDK APIs are redirected to SDK compat shims (mibsdk.compat.*);
#    Retrotranslator looks up _Type statics for members added to existing classes (e.g. _Map.merge).
"$JDK8/bin/java" -cp "$T/retrotranslator-transformer-1.2.9.jar:$T/retrotranslator-runtime-1.2.9.jar:$S/compat/out" \
  net.sf.retrotranslator.transformer.Retrotranslator \
  -srcdir "$OUT/j5" -destdir "$OUT/j4" -target 1.4 -embed spike.rt -stripsign \
  -backport "java.util.function:mibsdk.compat.java.util.function;java.util:mibsdk.compat.java.util;java.lang:mibsdk.compat.java.lang"
cp -R "$S/compat/out/" "$OUT/j4/"

# 4. linkage check against the car's JCL only (nothing from the desktop JDK)
"$JDK17/bin/java" --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED "$S/LinkCheck.java" "$OUT/j4" "$S/carjcl" \
  | tee "$OUT/linkcheck.log" || true

(cd "$OUT/j4" && "$JDK8/bin/jar" cf "$OUT/hello.jar" .)
echo "class versions:"; find "$OUT/j4" -name '*.class' -exec file {} \; | sed 's/.*: //' | sort | uniq -c
