# Troubleshooting

## First checks

1. `installer/mibsdk-dev status <unit-ip>` — is the plugin `ENABLED`?
2. `installer/mibsdk-dev log <unit-ip>` — what is the last `STATUS` or `ERROR` line?
3. Does the same plugin pass `./gradlew test` on the host?
4. Is the SDK itself running, or is the unit in safe mode?

## Plugin states

| State | Cause | Fix |
|---|---|---|
| `BLOCKED_INVALID` | Unreadable jar, invalid `plugin.json`, or jar not named `<id>.jar` | Rebuild with `mibPluginJar`; check the manifest |
| `BLOCKED_PERMISSIONS` | A declared permission is not granted | Approve the full list, or `mibsdk-dev grant` |
| `BLOCKED_DEPENDENCY` | A `"dependencies"` entry is missing, blocked, disabled or cyclic | Fix the dependency |
| `FAILED` | Loading the class or `onEnable` threw | Read the log; fix and push |
| `QUARANTINED` | 3 failures in 60 s or a 30 s hang | Fix and push; a reload re-enables it |
| `DISABLED` | Switched off in the Plugins menu or by a reload | Re-enable it |

## Build errors

### `permission not declared in plugin.json: X`

Your code calls an API that needs `X` and the manifest does not declare it. Add `X` to `"permissions"`.
Granting it on the unit does not help: an undeclared permission can never be used.

### `permission not granted: X`

The manifest declares `X` but the user has not approved it. Install with approval or grant it. Normally
the plugin is `BLOCKED_PERMISSIONS` before it gets this far.

### `... needs api 5; plugin.json declares api 4`

Raise `"api"` to the level the call needs. The error names the call site. See [API levels](api-levels.md).

### `reference(s) the car cannot resolve`

`linkCheck` names the missing class or member, the call site and usually a hint. Common causes:
`java.util.regex`, `String.join`, `java.util.concurrent`, streams, `java.time`. See
[Java compatibility](compatibility.md).

### `1 call(s) need platform.unrestricted`

You used reflection, exec, native loading or a raw socket. Use `ctx.net()` for TCP, or declare
`platform.unrestricted` if you really need the escape hatch.

### `main class ... is not among the plugin's classes`

`"main"` in `plugin.json` does not match the class you wrote, or the package is wrong.

### `icon 'x' is declared in plugin.json but src/main/icons/x.png does not exist`

Add the file or remove the name from `"icons"`.

### `icon ... must be 8-bit RGBA` / `at most 72 px` / `32 KiB`

Re-export the PNG with those properties. Palette PNGs and interlaced PNGs are rejected.

### `parent directory ... does not exist` / wrapper errors

You copied the project without the `gradle/wrapper` directory. Copy the whole `templates/plugin` tree,
or run `gradle wrapper`.

### `mibsdk.repo is not set`

The template's `gradle.properties` has an empty `mibsdk.repo`, which means "use the published
repository". If you are offline or testing a local SDK build, set it to a checkout's `build/repo`:

```properties
mibsdk.repo=file:///absolute/path/to/mib2q-plugin-sdk/build/repo
```

### `Plugin [id: 'mibsdk.plugin', version: '...'] was not found`

The repository URL does not contain that plugin version, or `mibsdk.repo` points at the wrong checkout.
Publish the SDK with `./gradlew publishLocal`, and make the version in `build.gradle.kts` match
`mibsdk.version`.

### `mibsdk: mibsdk.mibpkg is not set`

`mibpkgPackage` needs the host binary. Build it with `cargo build --release` in the mibpkg repository and
set `mibsdk.mibpkg`, or put `mibpkg` on `PATH`.

## Runtime errors

### A status icon is too large

`a status icon is at most 30x30 px, this one is 72x72` — use a 30 px icon for `statusItem` and `toast`
(a 20 px icon still works in the bar).

### A toggle does not stick

The HMI does not flip a checkbox by itself. Store the requested value and call `page.update(spec())`.

### A blank row icon after a push

Icons arrive only with a package. Install the `.mpk`.

### A row or page does not appear

- The feature may be missing this boot: check `ctx.has(Features.UI_PAGES)`,
  `Features.SLOT_CAR_MENU`, `Features.SLOT_STATUS_BAR`.
- The Car screen reads its rows when it opens; reopen it after changing a row.
- A plugin with a missing grant never starts.

### The plugin is quarantined

Three callback failures in 60 s, or a callback still running after 30 s. The log names the failure. Fix
the exception or split the slow work; then push or reload.

### The unit boots in safe mode

Three unhealthy boots in a row. The SDK runs with no plugins and restores pending overrides. Use
`mibsdk-dev safe-mode off` after fixing the plugin, and inspect the log from the previous boot.

## Diagnostics

```sh
installer/mibsdk-dev status <unit-ip>
installer/mibsdk-dev log <unit-ip>
installer/mibsdk-dev data-clear <id> <unit-ip>   # corrupted storage
installer/mibsdk-dev remove <id> <unit-ip>       # then reinstall
```

In a host test, `h.await` prints the pages and the runtime log when it fails. `h.log()` returns the log.

## Still stuck?

Open an issue with:

- the HMI build from `/ifs/version_info.txt`;
- the SDK version;
- the plugin's `plugin.json` and the failing code;
- the relevant log lines (remove VIN, IMSI and location first).

## Related

- [Install and debug](../getting-started/install-and-debug.md)
- [mibsdk-dev](cli.md)
- [Safety model](../explanation/safety-model.md)
