# `mibsdk-dev`

`installer/mibsdk-dev` is a bash wrapper around the unit's mibpkg, run from your computer over SSH. It
uploads files to `/tmp`, passes mibpkg's output and exit code through, and never reads the `j9` command
line (which embeds the VIN and IMSI).

```text
usage: mibsdk-dev <command> [args] [host]      (host: the last argument, else $MIBSDK_HOST)
```

There is no default host.

## Commands

| Command | What it does |
|---|---|
| `install` | Upload and install the SDK package (`dist/build/dist/mibsdk-*.mpk`, or `$MIBSDK_MPK`) |
| `uninstall [--purge] [--force]` | Remove the SDK. `--purge` deletes plugin data; `--force` uninstalls while overrides are active |
| `install-plugin <file.mpk> [--approve=<perm,...>]` | Install or update a plugin package |
| `remove <id> [--purge\|--purge-data]` | Remove a plugin; keeps data unless purged |
| `push <plugin.jar>` | Upload a jar and its stamp, then reload the plugin |
| `grant <id> <perm,...>` | Grant permissions; an empty list revokes all |
| `safe-mode on\|off` | Force safe mode on or off; `off` also resets the boot counter |
| `boot-state set-wrap <n>` | Set the boot-guard wrap count |
| `data-clear <id>` | Delete a plugin's storage; restart afterwards to free the quota |
| `status` | The SDK, plugins, states, grants, ledger and `STATUS` lines |
| `log` | Print `/tmp/mibsdk.log` |
| `restart` | Restart the HMI (a bench reset takes 2–3 minutes) |
| `ui-forcefail factory\|screen\|off` | Developer switch to make the SDK UI fail on purpose; read at boot |

## Exit codes

`mibsdk-dev` passes mibpkg's status through:

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Failed or busy |
| 2 | Bad usage |
| 3 | Permissions need approval; re-run with `--approve=...` |

## Environment

| Variable | Meaning |
|---|---|
| `MIBSDK_HOST` | The unit address when it is not the last argument |
| `MIBSDK_DIST` | The dist directory (default `dist/build/dist`) |
| `MIBSDK_MPK` | The SDK package to install |

## Examples

```sh
installer/mibsdk-dev status 10.0.0.5
installer/mibsdk-dev log 10.0.0.5
installer/mibsdk-dev push build/mibsdk/coffee-timer.jar 10.0.0.5
installer/mibsdk-dev grant coffee-timer ui.slot.carmenu,ui.slot.statusbar 10.0.0.5
installer/mibsdk-dev safe-mode on 10.0.0.5
```

## SSH details

The unit uses an old RSA host key. The tool passes:

```text
-o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa
-o BatchMode=yes -o ConnectTimeout=8
```

It logs in as `root`. Key-based auth must already work.

## Related

- [Install and debug](../getting-started/install-and-debug.md)
- [Troubleshooting](troubleshooting.md)
