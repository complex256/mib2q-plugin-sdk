# Firmware support

## The supported build

| | |
|---|---|
| Unit | MHI2Q (MIB2 High, QNX 6.5) |
| HMI build | `HMI_AU_MIB2_HQC_NAR_G22_C10_180803` |
| Variant | `FMQ-P-TNSL-US-AU-MLE` |
| How it is matched | The `HMI = ...` line of `/ifs/version_info.txt`, compared exactly |

The check is strict. On any other build the SDK finds no matching adapter and stays off; no plugin loads.
The SDK package's own manifest also names that build and the `MHI2Q` unit, so mibpkg refuses to install
it elsewhere.

## Why so strict

The adapter does not sit on a stable public API. It:

- starts a bundle through the stock 3SOFT OSGi kernel;
- installs a `TraceConfigurator` to wrap DSI services;
- shadows a few stock classes;
- looks up framework services by name and builds stock widgets;
- depends on the exact menu, status-bar, icon and DSI behaviour of one HMI build.

A different build moves classes, changes service names, changes screen factories and changes the status
bar. Running anyway would be guesswork, and the failure mode is a boot loop.

## Other units

These are not supported:

- other MHI2 High builds (different regions, newer software);
- the non-Q MIB2 High;
- MIB2 Standard, MIB3, PCM, other vehicle platforms;
- other vehicle platforms.

Each needs a new **firmware adapter**. The plugin API is firmware-neutral; the adapter is the only layer
that knows a car.

## What a second adapter would need

1. A map of the HMI's Java: how it boots, how bundles are registered, which services it exposes.
2. A bundle activator and configurator chain for that build.
3. A version check for that build.
4. DSI/parking bridges and the UI implementation: Pages, Car rows, status line, toasts, dialogs, icons.
5. Bench evidence for every UI path and the parking override.
6. A package manifest that names the supported build.

The existing adapter is the reference implementation. It is intentionally the only module that touches
the manufacturer's stock packages and `org.dsi.*`.

The full investigation and implementation guide is
[Adding a firmware adapter](../contributing/adapters.md). In short:

1. Read `/ifs/version_info.txt` and `lsd.sh`.
2. Pull `lsd.jxe`, `DSITracer.jar` and the boot jars; convert the JXE with `jxe2jar`.
3. Build a map of the launch chain, bundles, DSI services, screen factories, status bar and input.
4. Copy the `adapter-hqc` module shape and implement `PlatformAdapter` in stages.
5. Prove every path on a bench unit before a car.

Do not guess ids, class names or service names from another firmware. The adapter guide lists the exact
classes to read, the commands to use, and the evidence to collect.

## Compatibility with other mods

The SDK replaces some stock classes and boot configuration. mibpkg refuses to install it while
a third-party mod that shadows the same classes is installed. Other mods that shadow stock classes may
conflict for the same reason.

## Checking a unit

Read the HMI line:

```sh
grep '^HMI = ' /ifs/version_info.txt
```

If it is not exactly the supported build, stop. The SDK will not run.

## Related

- [Install the SDK](../getting-started/install-sdk.md)
- [Architecture](architecture.md)
- [Safety model](safety-model.md)
