# Build and test the SDK

## Requirements

- JDK 17 (Gradle runs on it).
- JDK 8 (the toolchain detects or downloads one).
- The stock `lsd.jar` and `DSITracer.jar` for `adapter-hqc` and `dist`.
- Host `mibpkg` for packaging.
- Python 3 for the installer and icon tests, and for the docs site.
- MkDocs Material for the docs site.

A plugin-only checkout does not need the stock jars. The SDK's `check` does, because it builds the
adapter and the distribution.

## Configure local paths

Put these in `$GRADLE_USER_HOME/gradle.properties` (by default `~/.gradle/gradle.properties`):

```properties
mibsdk.stockLsdJar=/path/to/lsd.jar
mibsdk.stockDsiTracerJar=/path/to/DSITracer.jar
mibsdk.mibpkg=/path/to/mibpkg
mibsdk.jdk8Home=/path/to/jdk8        # optional
```

There are no machine-specific defaults. A missing required property fails with a message naming it. To pull
the stock files from a unit, see [Stock files](stock-files.md).

## Gates

```sh
./gradlew check
./gradlew :build-logic:test
python3 -m unittest discover -s installer -p 'test_*.py'
```

`check` runs:

- every module's tests;
- `classVersionCheck`, `linkCheck` and `permissionScan`;
- build-logic's tests (through the root `check`);
- `standalonePluginCheck`, which scaffolds `templates/plugin` outside the project graph and builds it
  against `build/repo`;
- `carJclSignaturesCheck` when `mibsdk.stockLsdJar` is set.

Reports land in `<module>/build/reports/mibsdk/`.

## Common commands

```sh
./gradlew :api:test
./gradlew :plugins:coffee-timer:test
./gradlew :plugins:coffee-timer:mibPluginJar
./gradlew publishLocal
./gradlew newPlugin -Pid=my-plugin -Pdir=../my-plugin
./gradlew standalonePluginCheck
./gradlew :dist:assembleUnit
./gradlew :dist:mibpkgPackage
./gradlew carJclSignatures          # needs mibsdk.stockLsdJar
./gradlew pullStockJars -Punit=<unit-ip>   # copies the stock files off a unit
```

The `plugins/` projects are samples, built as subprojects of this build. A standalone plugin uses the
same task names without the `:plugins:<id>:` prefix.

## Layout

| Module | Notes |
|---|---|
| `api` | Plugin API. Additive changes only; `@since api N` tags are checked |
| `compat` | Java 1.4 source shims. No generics |
| `platform` | Adapter SPI and UI model |
| `runtime` | Loader, permissions, scheduler, UI, boot guard, ledger |
| `adapter-hqc` | Firmware adapter. Needs the stock jars |
| `adapter-fake` | Host fake car |
| `plugin-test` | Test harness |
| `build-logic` | Included build with the Gradle conventions |
| `dist` | Unit jars and the SDK package |
| `templates/plugin` | The standalone template; tested by `standalonePluginCheck` |
| `plugins/` | Samples: `hello`, `crashy`, `coffee-timer` |
| `installer/` | `mibsdk-dev` and its Python tests |
| `docs/` | User docs and internal records |

## Docs

```sh
python3 -m pip install -r docs/requirements.txt
./gradlew docsApi                  # Dokka HTML into build/docs-api
./gradlew docsSite                 # MkDocs into build/docs-site, with API and Maven repo
mkdocs serve                       # live preview at http://127.0.0.1:8000
```

The `docsSite` task:

1. generates the Dokka API reference;
2. builds the MkDocs site with `--strict`;
3. copies Dokka into `build/docs-site/api/`;
4. copies `build/repo` into `build/docs-site/maven/` when it exists.

`tools/check_docs.py` verifies code blocks marked `<!-- file: ... -->` against the sample files and checks
the documented versions against source.

## Code style

- Java source in `api`, `runtime`, `platform`, `compat` and `adapter-*` stays Java 1.4 compatible except
  where a module explicitly compiles with a newer release.
- Keep the plugin class loader allow-list and the build's `Toolchain.ALLOWED_PARENT_PREFIXES` in sync.
- Add a test for every new runtime state, permission or UI path.
- Do not add a dependency to the on-unit jars unless it is backported and link-checked.

## Related

- [Releasing](releasing.md)
- [Docs style guide](docs-style-guide.md)
- [Build tasks](../reference/build-tasks.md)
