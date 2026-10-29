# Releasing

## Versioning

| Version | Where | Meaning |
|---|---|---|
| SDK version | `mibsdk.version` in `gradle.properties` | The `mibsdk` mibpkg package and Maven artifacts |
| Plugin API | `api` in `plugin.json`; `SdkInfo.API_VERSION` | The newest API level the SDK implements |
| Plugin version | `version` in a plugin's `plugin.json` | The plugin package version |
| Template version | `templates/plugin/build.gradle.kts` | Must equal `mibsdk.version` |

The SDK follows semver. Bench builds may carry a `+bench.N` tag, but they are not releases.

## Release checklist

1. **Bump `mibsdk.version`** in `gradle.properties`.
2. **Bump the template** to the same version in `templates/plugin/build.gradle.kts`. `standalonePluginCheck`
   fails if they differ.
3. **Add a CHANGELOG entry** in Keep a Changelog style. Include SDK changes and plugin API changes.
4. **Update the API level table** if `SdkInfo.API_VERSION` changed. `ApiLevelsTest` checks `@since` tags.
5. **Update the docs** that name the version or a limit.
6. **Run the gates:**

   ```sh
   ./gradlew check
   ./gradlew :build-logic:test
   python3 -m unittest discover -s installer -p 'test_*.py'
   python3 tools/check_docs.py
   ./gradlew docsSite
   ```

7. **Publish the artifacts:**

   ```sh
   ./gradlew publishLocal
   ```

8. **Tag and push:**

   ```sh
   git tag v0.5.0
   git push origin main --tags
   ```

## What the tag publishes

The `docs` workflow builds the site, the Dokka API reference and the Maven repository, then deploys them
to the `gh-pages` branch. GitHub Pages must be configured to serve that branch.

- the documentation site at `/`;
- the Dokka API reference at `/api/`;
- the Maven repository at `/maven/`.

The Maven repository contains `dev.complex256.mibsdk:api`, `compat`, `platform`, `runtime`,
`adapter-fake`, `plugin-test`, the `mibsdk.plugin` and `mibsdk.car-library` Gradle plugins, and their
markers. A plugin resolves them from:

```text
https://complex256.github.io/mib2q-plugin-sdk/maven
```

Released versions are immutable. Do not republish a version that already exists on Pages.

## The SDK package

`dist/build/dist/mibsdk-<version>.mpk` needs the unit's stock jars, which are not in CI. Build it locally and
attach it to the GitHub release:

```sh
./gradlew :dist:mibpkgPackage
```

The plugin artifacts do not need the stock jars and are published by CI.

## API changes

- Additive only. A newer SDK runs every older plugin unchanged.
- Every new public member gets `@since api N`.
- `ApiLevels` in build-logic must know the member, or `permissionScan` cannot enforce the declared level.
- `SdkInfo.API_VERSION` and `MIN_API_VERSION` move only with a documented compatibility decision.
- A new API level needs tests for the new members and a docs update.

## Hotfixes

A hotfix is a patch release: bump the patch version, add a CHANGELOG entry, run the gates, tag. If the
fix changes plugin behaviour, update the relevant guide and reference page in the same commit.

## Related

- [Build and test](build-and-test.md)
- [API levels](../reference/api-levels.md)
- [CHANGELOG](https://github.com/complex256/mib2q-plugin-sdk/blob/main/CHANGELOG.md)
