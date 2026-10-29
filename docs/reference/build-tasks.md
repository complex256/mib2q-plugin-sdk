# Build tasks

## Plugin tasks

The `mibsdk.plugin` convention adds these tasks to your project:

| Task | Output | What it does |
|---|---|---|
| `mibPluginJar` | `build/mibsdk/<id>.jar` and `.jar.stamp` | Compiles, backports, checks and packs the plugin |
| `mibpkgPackage` | `build/mibsdk/<id>-<version>.mpk` | Stages the jar, stamp, icons and `manifest.toml`, then runs the host mibpkg |
| `mibpkgPackageStage` | `build/mibpkg-stage/` | Stages the package contents without packing |
| `test` | Test reports | Runs host tests on the real runtime and fake car |
| `backport` | `build/v48/` | Lowers class files to v48 |
| `classVersionCheck` | report | Every class is v48 |
| `linkCheck` | `build/reports/mibsdk/linkcheck.txt` | Every reference resolves against the car class library |
| `permissionScan` | `build/reports/mibsdk/permissions.txt` | Permissions, api level, reflection/exec |
| `mibPackage` | — | Deprecated alias for `mibpkgPackage` |

The normal commands:

```sh
./gradlew test
./gradlew mibPluginJar
./gradlew mibpkgPackage
./gradlew build          # assemble + check
```

`mibPluginJar` depends on `classVersionCheck`, `linkCheck` and `permissionScan`. A failure in any of them
fails the jar.

## Build properties

Set these with `-P` or in `$GRADLE_USER_HOME/gradle.properties`:

| Property | Used by | Meaning |
|---|---|---|
| `mibsdk.repo` | Plugin settings | The Maven repository that holds the SDK artifacts. Empty means the published repository |
| `mibsdk.mibpkg` | `mibpkgPackage` | The host mibpkg binary. Default: `mibpkg` on `PATH` |
| `mibsdk.jdk8Home` | `backport`, `compat` | A JDK 8 home. Default: the Gradle toolchain's JDK 8 |
| `mibsdk.stockLsdJar` | SDK maintainers | jxe2jar's `lsd.jar`. Not needed for a plugin build |
| `mibsdk.stockDsiTracerJar` | SDK maintainers | The stock `DSITracer.jar`. Not needed for a plugin build |

A property that points at a missing file fails with a message that names the property. There are no
machine-specific defaults under your home directory.

## SDK tasks

From a checkout of this repository:

| Task | What it does |
|---|---|
| `./gradlew check` | All module tests and checks, build-logic tests, the standalone template build and `carJclSignaturesCheck` when a stock jar is configured |
| `./gradlew publishLocal` | Publishes the SDK artifacts and Gradle plugins to `build/repo` |
| `./gradlew newPlugin -Pid=<id> -Pdir=<path> [-Ppackage=<pkg>]` | Scaffolds `templates/plugin` into a new project |
| `./gradlew standalonePluginCheck` | Scaffolds the template outside the project graph and builds it against `build/repo` |
| `./gradlew :dist:assembleUnit` | The three unit jars in `dist/build/dist/` |
| `./gradlew :dist:mibpkgPackage` | The SDK `.mpk` |
| `./gradlew carJclSignatures` | Regenerates the bundled car class-library list (needs `mibsdk.stockLsdJar`) |
| `./gradlew carJclSignaturesCheck` | Fails when the bundled list is out of date |
| `./gradlew pullStockJars -Punit=<host>` | Copies `lsd.jxe`, `DSITracer.jar` and `version_info.txt` off a unit and converts the JXE with jxe2jar |
| `./gradlew docsSite` | Builds this documentation site (needs Python and MkDocs) |

`./gradlew check` for the SDK needs both stock jars if you build `adapter-hqc` and `dist`. A plugin-only
build does not.

## Reports

A plugin's checks write:

- `build/reports/mibsdk/linkcheck.txt` — empty when clean;
- `build/reports/mibsdk/permissions.txt` — permission and api-level findings, including warnings.

An up-to-date build does not re-run a check, so Gradle prints nothing then; read the report if you want
the previous warning.

## Related

- [Building and testing](../contributing/build-and-test.md)
- [Troubleshooting](troubleshooting.md)
