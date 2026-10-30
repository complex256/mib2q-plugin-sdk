# Install and debug

## Package the plugin

```sh
./gradlew mibpkgPackage -Pmibsdk.mibpkg=/path/to/mibpkg
```

The output is `build/mibsdk/<id>-<version>.mpk`. It contains the stamped jar, your icons, their generated
reflections and a `manifest.toml` that mibpkg reads. Inspect the staged files in
`build/mibpkg-stage/` if a package looks wrong.

A plugin with `"api": 6` needs SDK 0.4.x. mibpkg refuses it on an older SDK.

## Install the package

=== "mibpkg web interface"

    Open mibpkg from your phone, upload the `.mpk`, review the permissions and approve. mibpkg writes
    the jar, the icons and the grants, then asks the running SDK to load the plugin. No HMI restart is
    needed.

=== "mibsdk-dev"

    ```sh
    installer/mibsdk-dev install-plugin build/mibsdk/coffee-timer-1.0.0.mpk <unit-ip>
    # exit 3 lists the permissions; re-run with:
    installer/mibsdk-dev install-plugin build/mibsdk/coffee-timer-1.0.0.mpk \
        --approve=ui.slot.carmenu,ui.slot.statusbar <unit-ip>
    ```

`mibsdk-dev` reads the unit address from the last argument or `MIBSDK_HOST`.

## The fast development loop

After the package is installed once, push only the jar:

```sh
./gradlew mibPluginJar
installer/mibsdk-dev push build/mibsdk/coffee-timer.jar <unit-ip>
```

The runtime hot-reloads it within a couple of seconds. A push carries no icons and no new permissions:

- a new icon needs a new package;
- a new permission needs `installer/mibsdk-dev grant <id> <perm,...> <unit-ip>`, and the manifest must
  declare it.

## Logs

`ctx.log().info/warn/error` writes to an in-memory ring buffer. The runtime mirrors it to
`/tmp/mibsdk.log` on the unit:

```sh
installer/mibsdk-dev log <unit-ip>
```

The log also contains the SDK's own `STATUS` lines, for example:

```text
STATUS coffee-timer FAILED mibsdk.runtime.UndeclaredPermissionException: permission not declared in plugin.json: ui.slot.carmenu at ...
```

In a host test, `h.log()` returns the same lines and `h.await` prints them when a test fails.

## Plugin states

| State | Meaning | What to do |
|---|---|---|
| `ENABLED` | Running | |
| `BLOCKED_INVALID` | Unreadable jar, invalid `plugin.json`, or the jar is not named `<id>.jar` | Rebuild with `mibPluginJar` |
| `BLOCKED_PERMISSIONS` | A declared permission is not granted; permissions are all or nothing | Approve or grant the full list |
| `BLOCKED_DEPENDENCY` | A declared dependency is missing, blocked, disabled or in a cycle | Fix the dependency |
| `FAILED` | Loading the class or `onEnable` threw; the reason is in the log | Fix and push |
| `QUARANTINED` | Three callback failures in 60 s, or one callback hung for 30 s | Fix and push; a push or reload starts it again |
| `DISABLED` | Switched off in the Plugins menu or by a reload | |

## Quarantine and safe mode

Every callback runs inside a catch-all. One exception is logged and the plugin keeps running. The third
within a minute quarantines it: it is disabled, its registrations are cancelled and the Plugins menu
says why. A callback still running after 30 s quarantines it as hung. The `crashy` sample does this on
purpose if you want to watch it.

If the HMI fails to come up healthy (60 s without a crash) three boots in a row with plugins enabled, the
next boot is in **safe mode**: the SDK runs with no plugins, restores pending vehicle overrides, and the
Plugins menu reads **Plugins (safe mode)**.

```sh
installer/mibsdk-dev safe-mode on <unit-ip>     # force it while debugging
installer/mibsdk-dev safe-mode off <unit-ip>    # leave it and reset the boot counter
```

## Common failures

**`permission not declared in plugin.json: X`**

Your code used something that needs `X` but the manifest does not list it. Add it and rebuild. The build
catches this for known calls, so it is rare on the unit. Granting it on the unit does not help: an
undeclared permission can never be used.

**`permission not granted: X`**

The manifest declares `X` but the user has not approved it. Reinstall with approval or run
`mibsdk-dev grant <id> X <unit-ip>`. Normally such a plugin is `BLOCKED_PERMISSIONS` before it gets far
enough to throw this.

**`a status icon is at most 30x30 px, this one is 72x72`**

You passed the Car-row icon to `statusItem` or `toast`. Ship a 30 px icon (a 20 px one still works in
the bar) and use it there.

**`icon 'cup' is not declared in plugin coffee-timer's plugin.json "icons"`**

Add the name to `"icons"` and make sure `src/main/icons/<name>.png` exists.

**A blank row icon after `push`**

Icons only arrive with a package. Reinstall the `.mpk`.

**A toggle that does not stick**

You did not call `page.update(spec())` with the new state.

**Build failures**

`linkCheck` and `permissionScan` name the method that made the call. Start at the method they name. See
[Troubleshooting](../reference/troubleshooting.md) for the full list.

## There is no debugger

JDWP is disabled on the unit and there is no emulator. Test on the host first. On the unit, use the log,
`mibsdk-dev status`, and safe mode.

## Next

- [mibsdk-dev reference](../reference/cli.md)
- [Troubleshooting](../reference/troubleshooting.md)
- [Safety model](../explanation/safety-model.md)
