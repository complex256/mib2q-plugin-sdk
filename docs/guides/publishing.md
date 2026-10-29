# Package and publish a plugin

A plugin is distributed as a mibpkg package (`.mpk`). You can share it with a file, a release, or a
package index; there is no central registry.

## Build the jar

```sh
./gradlew mibPluginJar
```

```text
build/mibsdk/<id>.jar
build/mibsdk/<id>.jar.stamp
```

The jar contains the backported classes and `plugin.json`. The stamp records the checks that ran and the
jar's SHA-256. mibpkg refuses a jar without a valid stamp, so do not edit the jar after the build.

## Build the package

```sh
./gradlew mibpkgPackage -Pmibsdk.mibpkg=/path/to/mibpkg
```

```text
build/mibsdk/<id>-<version>.mpk
```

The package contains:

- the stamped jar;
- the icons from `src/main/icons/`, plus a generated `<name>.r.png` reflection for each icon taller than
  20 px;
- a generated `manifest.toml` with the id, version, unit compatibility, api level, permissions and
  icons.

The staged files are in `build/mibpkg-stage/`. The task is `mibpkgPackage`; `mibPackage` is a
deprecated alias.

## Versioning

The plugin's version is semver in `plugin.json`:

```json
{ "version": "1.2.3" }
```

mibpkg uses it to order updates. Raise it for every release you publish. Pre-release tags such as
`1.2.3-beta.1` are accepted.

The `"api"` field is different from the version: it is the newest plugin API your code uses. See
[API levels](../reference/api-levels.md).

## Install and approve

mibpkg shows the plugin's permissions in plain words and installs only after approval:

- Web interface: upload the `.mpk` and accept the sheet.
- CLI: `mibpkg install-file <id> ...`; exits 3 until you re-run with `--approve=<perm,...>`.

Installing writes:

- `plugins/<id>.jar`;
- `icons/<id>/<sha16>.png` and `index.properties`;
- a line in `grants.properties`.

The running SDK loads the plugin within a few seconds; no HMI restart is needed.

## Updating

Build a new `.mpk` with a higher version and install it. mibpkg replaces the jar, icons and grants. If
the new version needs a new permission, the approval sheet shows only the new permission.

A jar push during development is faster but carries no icons and no permissions. Once you are happy,
install the package so the unit has the full set.

## Removing

```sh
installer/mibsdk-dev remove coffee-timer <unit-ip>            # keep storage
installer/mibsdk-dev remove coffee-timer --purge <unit-ip>    # remove storage too
```

Removing a plugin releases its vehicle overrides and cancels its registrations. Its storage stays unless
you purge it.

## Sharing

A plugin repository should contain:

- the source;
- the Gradle wrapper;
- a `README.md` with what the plugin does, its permissions and how to install it;
- a license;
- optionally, the built `.mpk` as a release asset.

Do not commit `build/`, `.gradle/` or machine-specific `mibsdk.repo` values.

## Related

- [plugin.json reference](../reference/plugin-manifest.md)
- [Build tasks reference](../reference/build-tasks.md)
- [Install and debug](../getting-started/install-and-debug.md)
