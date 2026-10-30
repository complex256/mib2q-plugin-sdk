# Getting started

This section takes you from a blank machine to a plugin running on the head unit. Read the pages in
order the first time; later, use the [guides](../guides/pages-and-inputs.md) and
[reference](../reference/plugin-manifest.md) as needed.

<div class="grid cards" markdown>

-   **[Prerequisites](prerequisites.md)**

    JDK 17, JDK 8, the Gradle wrapper, and mibpkg for packaging.

-   **[Install the SDK](install-sdk.md)**

    Install the `mibsdk` package on the unit once, then verify the Plugins menu.

-   **[Create a plugin](create-a-plugin.md)**

    Start from the template, scaffold with `newPlugin`, or write the five files by hand.

-   **[Project layout](project-layout.md)**

    What lives in a plugin, what ships to the unit, and how the Java and native sides fit together.

-   **[Tutorial](tutorial.md)**

    Build the coffee-timer plugin: a Car row, a page, a slider, a status item and a toast.

-   **[Test your plugin](test-your-plugin.md)**

    Run the real runtime against a fake car on your computer, with no unit attached.

</div>

## The short version

The examples use SDK **0.6.3**. If you already have a working unit and a JDK:

```sh
git clone https://github.com/complex256/mib2q-plugin-sdk.git
cp -R mib2q-plugin-sdk/templates/plugin my-plugin
cd my-plugin
./gradlew test
./gradlew mibPluginJar
```

That produces `build/mibsdk/my-plugin.jar`. Install the SDK package on the unit once, then install your
plugin as a package (`.mpk`) or push the jar during development. See
[Install and debug](install-and-debug.md).

## Before you install anything

This is experimental software for a car. Read the [safety model](../explanation/safety-model.md) first.
The SDK has been verified on a bench unit, not yet on a car, and installing it is likely to void your
warranty.
