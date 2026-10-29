# Prerequisites

## To build a plugin

| Need | Why | How the build finds it |
|---|---|---|
| **JDK 17** | Gradle runs on it and compiles your source with modern syntax | The Gradle toolchain. Any JDK 17 works |
| **JDK 8** | Retrolambda, Retrotranslator, ProGuard and the Java 1.4 compile | The Gradle toolchain. An installed JDK 8 is detected; the foojay resolver downloads one if needed. Override with `-Pmibsdk.jdk8Home=<home>` |
| **Gradle** | The build | The wrapper in the template (`./gradlew`). Gradle 8.13 |
| **Internet** | The first build downloads Jabel, Retrolambda, Retrotranslator, ProGuard, ASM, JUnit and the Kotlin plugin | Maven Central |
| **mibpkg** | Only for `mibpkgPackage`, which builds the `.mpk` | `-Pmibsdk.mibpkg=<path>`, or `mibpkg` on `PATH` |

Build the host `mibpkg` with `cargo build --release` in the
[mibpkg repository](https://github.com/complex256/mibpkg). You do not need it to compile or test a plugin.

You do **not** need the unit's stock `lsd.jar` or `DSITracer.jar` to build a plugin. `linkCheck` uses a
committed signature list of the car's class library. Only SDK maintainers building `adapter-hqc` or
`dist` need the stock jars; [Stock files](../contributing/stock-files.md) automates pulling them off a
unit.

## To install on a unit

| Need | Why |
|---|---|
| An **MHI2Q** on HMI build `HMI_AU_MIB2_HQC_NAR_G22_C10_180803` | The only build with an adapter |
| **mibpkg 0.9.1** or later on the unit | Package installation and the `mibpkg sdk` verbs |
| A way to reach the unit | mibpkg's web interface, the SD card, or SSH |
| `bash` and `ssh` on your computer | For the optional `installer/mibsdk-dev` bench tool |

The unit's SSH server uses an old RSA host key. `mibsdk-dev` passes the right options for you.

!!! warning "Phone-projection mods"
    mibpkg refuses to install the SDK while a third-party phone-projection mod that shadows the same stock
    classes is installed.

## Where the SDK artifacts come from

A plugin resolves `dev.complex256.mibsdk:api`, `compat`, `plugin-test` and the `mibsdk.plugin` Gradle
plugin from a Maven repository. The template uses the published repository by default:

```text
https://complex256.github.io/mib2q-plugin-sdk/maven
```

If you are working on the SDK itself, publish the artifacts to a local file repository and point your
plugin at it:

```sh
# in the SDK checkout
./gradlew publishLocal

# in your plugin project
./gradlew mibPluginJar -Pmibsdk.repo="file:///absolute/path/to/mib2q-plugin-sdk/build/repo"
```

`mibsdk.repo` in `gradle.properties` is the persistent form of the same setting. An empty value means
"use the published repository".

## Optional: Python 3

The tutorial's icon generator and the SDK's icon tools are Python 3 scripts with no third-party
dependencies. Any image editor that exports 8-bit RGBA PNGs works instead.
