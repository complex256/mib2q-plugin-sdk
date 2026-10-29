# `plugin.json`

Every plugin has `src/main/resources/plugin.json`. The build reads it for packaging and checks; the
runtime reads the copy at the jar root.

```json
{
  "id": "coffee-timer",
  "name": "Coffee Timer",
  "version": "1.0.0",
  "author": "your-name",
  "api": 5,
  "main": "tutorial.coffeetimer.CoffeeTimerPlugin",
  "permissions": ["ui.slot.carmenu", "ui.slot.statusbar"],
  "icons": ["cup", "cup-20"]
}
```

## Fields

| Field | Required | Rules |
|---|---|---|
| `id` | Yes | 2–40 characters: a letter, then `a-z`, `0-9` and `-`. It names the jar, the mibpkg package, your storage and your icon directory. `mibsdk` is reserved |
| `name` | Yes | Free text shown in the Plugins menu and in mibpkg |
| `version` | Yes | Semver: `1.2.3` or `1.2.3-beta.1`. mibpkg uses it to order updates |
| `author` | Yes | Free text. Read by the runtime; ignored by the build |
| `api` | Yes | Integer 1–6: the newest plugin API your code uses |
| `main` | Yes | Fully qualified class that extends `mibsdk.api.MibPlugin` |
| `permissions` | No | Array of permission ids from [`api/permissions.json`](https://github.com/complex256/mib2q-plugin-sdk/blob/main/api/permissions.json) |
| `icons` | No | Array of icon names, `[a-z0-9-]{1,32}`, at most 8. Needs `"api": 3` or higher. Each needs `src/main/icons/<name>.png` |
| `dependencies` | No | Array of plugin ids that must be enabled first. Read by the runtime; not checked by the build |

## Validation

### Build

`PluginJson.read` fails the build for:

- missing `id`, `name`, `version`, `main`;
- an id outside the rule above, or `mibsdk`;
- a version that is not semver;
- an `api` outside 1–6, or a non-integer;
- an unknown permission, with a "did you mean" hint;
- an invalid, duplicate or excessive icon name;
- `"icons"` with an api below 3;
- a missing icon PNG, or a PNG that fails the format rules.

`permissionScan` additionally fails the build when:

- your code uses a permission the manifest does not declare;
- your code uses API newer than `"api"`;
- your code uses reflection, exec or raw sockets without `platform.unrestricted`.

It warns when a status-bar call names a declared icon larger than 20×20.

### Runtime

`ManifestParser` re-checks the manifest when the jar is loaded. A plugin whose manifest is invalid is
`BLOCKED_INVALID`. A plugin whose declared permissions are not all granted is `BLOCKED_PERMISSIONS` and
never starts.

The runtime accepts a slightly wider id range than the build (2–41 characters, starting with a letter or
digit). Use the build's rule; it is the one that determines whether a package can be created.

## Permissions

The catalog lives in [`api/permissions.json`](https://github.com/complex256/mib2q-plugin-sdk/blob/main/api/permissions.json):

| Id | Approval text |
|---|---|
| `vehicle.read` | Read vehicle state: ignition, gear, parking sensors |
| `vehicle.parking.write` | Change parking-sensor settings, for example mute the rear tones |
| `hooks.parking` | Watch and override parking-system messages |
| `ui.slot.carmenu` | Add a row to the Car menu |
| `ui.slot.statusbar` | Show an item in the status bar |
| `network` | Query local services on this unit (loopback only) |
| `network.outbound` | Connect to hosts outside this unit |
| `platform.unrestricted` | Unrestricted: may use reflection and stock HMI classes; the SDK cannot contain it |

See [Permissions](permissions.md) for the enforcement model.

## Icons

`"icons"` lists file names without the `.png` extension:

```text
src/main/icons/cup.png      declared as "cup"
src/main/icons/cup-20.png   declared as "cup-20"
```

The package carries them as `icons/<name>.png`; mibpkg installs them as `icons/<id>/<sha16>.png`.

## Related

- [Project layout](../getting-started/project-layout.md)
- [Icons](../guides/icons.md)
- [API levels](api-levels.md)
