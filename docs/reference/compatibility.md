# Java compatibility

The car runs IBM J9 2.3 with a Java 1.4 class library and loads only **class-file version 48**. You write
modern Java; the build lowers it for you. Modern **syntax** is fine. Modern **library** calls are not.

## Syntax you can use

- lambdas and method references;
- `var`;
- switch expressions and arrow switches;
- text blocks;
- enhanced `for`;
- generics;
- autoboxing;
- try-with-resources;
- Java 8+ annotations that Jabel understands.

The build compiles with JDK 17 and `--release 8`, then runs Retrolambda and Retrotranslator.

## Records are not supported

A `record` fails in `compileJava` with Jabel's message:

```text
error: Must be annotated with @Desugar
```

Use a final class with final fields instead.

## The car's class library is old

Retrotranslator backports some Java 5 API, including `StringBuilder`, `Integer.valueOf` and
`String.format`. The `compat` module supplies shims for:

- `Objects`;
- `AutoCloseable`;
- `Function`, `Consumer`, `Supplier`, `Predicate`, `BiFunction`, `BinaryOperator`, `UnaryOperator`,
  `BiConsumer`, `BiPredicate`;
- `IntFunction`, `IntPredicate`, `IntUnaryOperator`, `ToIntFunction`, `IntSupplier`,
  `BooleanSupplier`.

The numeric and `Long`/`Double` variants of the rest are missing.

## What `linkCheck` rejects

`linkCheck` compares every reference against a signature list of the car's class library and fails with
the reference and its call site:

```text
> LinkCheck: 1 reference(s) the car cannot resolve:
    member java/lang/String.matches(Ljava/lang/String;)Z
      used by tutorial/coffeetimer/CoffeeTimerPlugin.start
      hint: no regex on the car: use indexOf/substring or a loop
```

Known gaps:

| Missing | Use instead |
|---|---|
| `java.util.regex`, `String.matches`, `split`, `replaceAll` | `indexOf`, `substring`, a loop |
| `String.join` | `StringBuilder` |
| `java.util.concurrent` | `ctx.scheduler()` and the one-worker model; `synchronized` if needed |
| `java.time` | store millis; `System.currentTimeMillis()` |
| Streams (`List.stream()`) | loops |

If you use a class that Retrotranslator rewrote, the report also names the class you wrote, in
parentheses, so you can find the import to remove.

## No reflection or escape routes

The build rejects these unless you declare `platform.unrestricted`:

- `Class.forName`, `Method.invoke`, `Constructor.newInstance`;
- `Runtime.exec`, `System.exit`, native loading, `Thread.stop`;
- raw `Socket`, `ServerSocket`, `URL.openConnection`, `InetAddress.getByName`.

Use `ctx.net()` for TCP queries.

## No stock classes

Your class loader sees only:

- `java.*`
- `javax.*`
- `mibsdk.api.*`
- `mibsdk.compat.*`
- `net.sf.retrotranslator.runtime.*`

The manufacturer's stock packages and `mibsdk.runtime.*` are hidden. You cannot patch or call
the stock HMI. Changes to stock behaviour go through declarative hooks.

## Threads

All plugin code runs on one SDK-owned worker thread per plugin, one task at a time. Do not block it:

- no `Thread.sleep` loops;
- no long I/O; `ctx.net().query` is capped at 3 s;
- use `ctx.scheduler()` for periodic or delayed work.

You *can* start your own thread; the build does not stop you. Do not: the SDK cannot cancel or quarantine
work on it, and it keeps running after the plugin is disabled.

## Kotlin

Kotlin works. Apply `id("org.jetbrains.kotlin.jvm")` before `id("mibsdk.plugin")`. The convention sets
the Kotlin target to 1.8 and runs a shrink step before the backport. The mib2q-plugins monorepo's
`bike-rack` sample is the reference.

## Related

- [API levels](api-levels.md)
- [Troubleshooting](troubleshooting.md)
- [Project layout](../getting-started/project-layout.md)
