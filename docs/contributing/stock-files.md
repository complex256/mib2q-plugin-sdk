# Stock files for an SDK build

**A plugin build does not need any stock HMI files.** `linkCheck` uses a committed signature list of the car's
class library. You only need the stock jars to build the SDK's firmware adapter (`adapter-hqc`) and the
distribution (`dist`), or to regenerate the signature list.

The files are the manufacturer's. Never commit them, attach them to a release, or put them in a container image.

## Pull them from a unit

With SSH access to a unit:

```sh
./gradlew pullStockJars -Punit=<unit-ip>
```

The task runs `tools/pull-stock-jars.sh`, which copies:

| File | From | Why |
|---|---|---|
| `lsd.jxe` | `/ifs/lsd.jxe` | The HMI's class image; `jxe2jar` turns it into `lsd.jar` |
| `DSITracer.jar` | `/mnt/app/eso/hmi/lsd/DSITracer.jar` | The DSI tracer the adapter links against |
| `version_info.txt` | `/ifs/version_info.txt` | The `HMI = ...` line the adapter matches |

Add `-PwithJars=1` to also copy `/mnt/app/eso/hmi/lsd/jars/*.jar`. These are the boot jars that shadow
some classes; they are useful when investigating a new firmware.

The files land in `build/stock/`. The script writes a `build/stock/gradle.properties` with the two paths.
Add it to your Gradle user home, or pass the properties on the command line:

```sh
cat build/stock/gradle.properties >> "${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
```

## Convert `lsd.jxe`

The SDK needs `lsd.jar`, an ordinary jar. Use
[jxe2jar](https://github.com/luka-dev/jxe2jar):

```sh
git clone https://github.com/luka-dev/jxe2jar
python3 -m pip install bitstring==3.1.9
```

Then let the task convert it:

```sh
./gradlew pullStockJars -Punit=<unit-ip> -Pjxe2jarSrc=/path/to/jxe2jar/src
```

Or convert by hand:

```sh
PYTHONPATH=/path/to/jxe2jar/src python3 /path/to/jxe2jar/src/jxe2jar.py \
    build/stock/lsd.jxe build/stock/lsd.jar
```

A conversion writes about 30,000 classes and takes a minute or two. The output has known quirks: some
numeric constants are byte-swapped, and `InnerClasses` attributes can be missing. That is fine for
linking; it matters when decompiling.

## Verify

```sh
./gradlew :adapter-hqc:compileJava :dist:assembleUnit
```

If a property is missing, the build names it:

```text
mibsdk: mibsdk.stockLsdJar is not set
  jxe2jar's lsd.jar (the HMI's class library)
  Set it with -Pmibsdk.stockLsdJar=<path> or in ~/.gradle/gradle.properties; see README.md#toolchain
```

## Regenerate the signature list

After changing the supported firmware or the stock files:

```sh
./gradlew carJclSignatures -Pmibsdk.stockLsdJar=build/stock/lsd.jar
./gradlew carJclSignaturesCheck
```

The first writes `build-logic/src/main/resources/mibsdk/build/carjcl.sigs`; commit that file. It contains
class names, supertypes, member names and descriptors only — no bytecode.

## SSH notes

The unit uses an old RSA host key. The script passes the same options as `mibsdk-dev`:

```text
-o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa
-o BatchMode=yes -o ConnectTimeout=8
```

It logs in as `root`. Key-based auth must already work. If the unit is not reachable, check the address,
the network and `ssh root@<unit-ip>` by hand.

## Legal

The stock files are proprietary. Keep them on your machine, out of git, out of CI artifacts, and out of
anything you publish. The repository's `.gitignore` already excludes `build/`.

## Related

- [Build and test](build-and-test.md)
- [Adding a firmware adapter](adapters.md)
- [Firmware support](../explanation/firmware-support.md)
