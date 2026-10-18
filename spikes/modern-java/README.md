# Spike: modern Java / Kotlin on the MHI2Q's J9 (throwaway)

Question: can plugin authors write modern Java or Kotlin and run it on the head unit's IBM J9 2.3 /
CDC Foundation 1.1 (a Java 1.4 API), and can each plugin load in its own class loader?

**Answer: yes, both.** Tested on the bench MHI2Q (2026-10-18) in a separate, short-lived J9 next to the
running HMI. The HMI's j9 PID was unchanged throughout.

## Results on the car

| Test | Result |
|---|---|
| Java 1.4 class files (v48) | runs |
| Java 5 (v49) / Java 6 (v50) class files | **rejected** (`UnsupportedClassVersionError: bad major version`), even with `-noverify`. Converting down to v48 is mandatory. |
| Modern Java plugin (lambdas, method refs, generics, `var`, switch expressions, `instanceof` patterns, text blocks, default methods, try-with-resources, enum/string switch, autoboxing, threads) | 12/12 pass, both verified and `-noverify` |
| Kotlin plugin (lambdas + `map`/`filter`/`sum`, data class, sealed class + `when`, extension fn, null-safety, `getOrPut`, `sortedBy`/`joinToString`, thread lambda) | 8/8 pass, both verified and `-noverify` |
| Per-plugin `URLClassLoader`, loaded twice (simulates hot reload) | works for both. Two distinct loaders and classes; a plugin's static state is fresh on reload. |

## Pipeline

```
Java:   javac 17 --release 8 + Jabel ─┐
Kotlin: kotlinc -jvm-target 1.8 ───────┤ (+ ProGuard: shrink with kotlin-stdlib, drop LocalVariableTable)
                                       ▼
        Retrolambda  → v49 (lambdas → classes, default methods, try-with-resources)
        Retrotranslator → v48, rewriting missing JDK APIs to mibsdk.compat.* shims
        LinkCheck    → every referenced class/member must exist in the car's JCL (from lsd.jxe) or in the jar
```

`build.sh` (Java), `kbuild.sh` (Kotlin), `car/go.sh <ip>` (push and run on the unit).

## Learned (feeds the SDK design)

- **The car's JCL is missing things a modern author assumes:** `StringBuilder`, `java.util.regex`,
  `AutoCloseable`, `java.util.function`, `Objects`, `Map.merge`, `Long.sum`, `Integer.hashCode(int)` and
  `Locale.ROOT`. The SDK needs a compat-shim library (`mibsdk.compat.*`) plus a build-time **link check**
  against the car's real JCL. That check has to cover class headers (superclass/interfaces) and catch types
  as well as call sites: the first version missed `AutoCloseable`, and the car caught it at runtime.
- **Kotlin is viable.** Plugin plus the stdlib it actually uses comes to about 83 classes / 86 KB before
  backporting. Kotlin's inline-lambda debug names (`$i$a$-filter-…`) are illegal in v48 class files, so
  `LocalVariableTable` has to be stripped.
- **Retrotranslator's embedded runtime** adds ~57 (Java) / ~194 (Kotlin) classes per plugin. The SDK should
  ship it **once** as a shared runtime instead of embedding it per plugin, which cuts each plugin to a
  few KB. Its runtime references `java.nio.channels.Channel` and `sun.misc.Perf`; both are fallback paths
  that never ran.
- **On the unit:** `/ifs/jre/bin` holds the J9 libraries but not the `j9` launcher, and `/tmp` is
  `/dev/shmem` (files only, no directories). For a standalone J9, stage the launcher from the same
  firmware's `ifs_hmi` into `/ramdisk` and symlink in the unit's own libraries. The real SDK runs inside
  the HMI's JVM, so this only matters for tests.
- jxe2jar's `java/lang/Double.class` is unreadable, so LinkCheck reports it; it is present on the car.
