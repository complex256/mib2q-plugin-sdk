# Install the SDK

The SDK is a mibpkg package named `mibsdk`. Install it once per unit. Plugins are separate packages and
can be installed, updated and removed without reinstalling the SDK.

## Get the package

The package is `dist/build/dist/mibsdk-<version>.mpk` inside an SDK checkout:

```sh
./gradlew :dist:mibpkgPackage
```

This build needs the stock `lsd.jar` and `DSITracer.jar`, which cannot be distributed with the SDK. See
[Building the SDK](../contributing/build-and-test.md) if you need to produce the package yourself. If a
release asset is published for your version, use that instead.

## Install it

=== "mibpkg web interface"

    Open mibpkg's web interface from your phone (the address is shown in GEM → mibpkg), upload the
    `.mpk` and install it. mibpkg lists the permissions in plain words and asks you to approve them.

=== "mibpkg on the unit"

    ```sh
    mibpkg install-file mibsdk <upload-id> - --approve=...
    ```

    `mibpkg` exits with status **3** and lists the permissions until you re-run it with the approval.
    The exact upload flow is described in the mibpkg README.

=== "mibsdk-dev over SSH"

    ```sh
    installer/mibsdk-dev install <unit-ip>
    ```

    `mibsdk-dev` uploads `dist/build/dist/mibsdk-*.mpk`, runs mibpkg and passes its exit status
    through. Use `MIBSDK_HOST` instead of the last argument if you prefer.

## What the installer changes

All changes are on the writable app partition; no IFS reflash is needed.

- Adds the three SDK jars: `mibsdk-runtime.jar`, `mibsdk-adapter-hqc.jar` and
  `mibsdk-rt-retrotranslator.jar`.
- Adds the permission catalog.
- Adds a bundle and a DSITracer configurator.
- Adds the `HMICar=` and `HMISystem=` lines the HMI reads.
- Writes `grants.properties` for plugin permissions.

Uninstalling the package restores the stock files byte for byte. It does not delete plugin data unless
you ask it to.

## Verify

1. Restart the HMI or wait for the next boot.
2. Open **MENU → Plugins**. You should see the Plugins page with an **SDK** entry.
3. If the SDK could not initialise, the page header says **Plugins (safe mode)** and no plugin runs.
   Check `/tmp/mibsdk.log` or run `installer/mibsdk-dev log <unit-ip>`.

The SDK starts loading plugins 30 seconds after boot by default. A jar installed or pushed later loads
within a few seconds.

## Uninstall

```sh
installer/mibsdk-dev uninstall <unit-ip>            # keep plugin data
installer/mibsdk-dev uninstall --purge <unit-ip>    # remove plugin data too
```

Before uninstalling, make sure no plugin holds a vehicle override. `--force` exists for the case where
one does, but the stock value should be restored first.

## Next

- [Create a plugin](create-a-plugin.md)
- [Safety model](../explanation/safety-model.md)
