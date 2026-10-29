# Create a plugin

A plugin is its own Gradle project. It compiles against `mibsdk.api` from the published Maven repository
and is packaged into a `.mpk`; it does not need a checkout of this SDK.

## Option 1: copy the template

The fastest route is the standalone template:

```sh
git clone https://github.com/complex256/mib2q-plugin-sdk.git
cp -R mib2q-plugin-sdk/templates/plugin my-plugin
cd my-plugin
```

Rename the plugin in three places:

| File | Change |
|---|---|
| `src/main/resources/plugin.json` | `id`, `name`, `main`, `permissions` |
| `src/main/java/example/myplugin/MyPlugin.java` | package and class name |
| `src/test/java/example/myplugin/MyPluginTest.java` | package and class name |

Then build it:

```sh
./gradlew test
./gradlew mibPluginJar
```

You should see `BUILD SUCCESSFUL` and `build/mibsdk/my-plugin.jar`.

## Option 2: scaffold with `newPlugin`

From a checkout of the SDK, `newPlugin` copies the template and rewrites the id, package and class name:

```sh
./gradlew newPlugin -Pid=coffee-timer -Pdir=../coffee-timer -Ppackage=tutorial.coffeetimer
```

| Property | Meaning |
|---|---|
| `-Pid` | The plugin id in `plugin.json`. Two to forty characters from `a-z`, `0-9` and `-`, starting with a letter. `mibsdk` is reserved |
| `-Pdir` | Destination directory. It must not exist, or must be empty |
| `-Ppackage` | Java package. Defaults to `plugin.<id without dashes>` |

The task also writes `mibsdk.repo` in `gradle.properties`, pointing at the checkout's `build/repo`, so
run `./gradlew publishLocal` in the SDK checkout first if you use this route.

## Option 3: write the files yourself

A plugin is small. The minimum is a build file, a settings file, a manifest and a class.

```text
my-plugin/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/wrapper/
└── src/
    ├── main/
    │   ├── java/example/myplugin/MyPlugin.java
    │   └── resources/plugin.json
    └── test/java/example/myplugin/MyPluginTest.java
```

### `build.gradle.kts`

```kotlin
plugins {
    id("mibsdk.plugin") version "0.5.0"
}
```

That is the whole build file. The convention compiles, backports, checks, tests and packages the plugin.

### `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        val sdkRepo = providers.gradleProperty("mibsdk.repo").orNull?.takeIf { it.isNotBlank() }
            ?: "https://complex256.github.io/mib2q-plugin-sdk/maven"
        maven { url = uri(sdkRepo) }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        val sdkRepo = providers.gradleProperty("mibsdk.repo").orNull?.takeIf { it.isNotBlank() }
            ?: "https://complex256.github.io/mib2q-plugin-sdk/maven"
        maven { url = uri(sdkRepo) }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "my-plugin"
```

### `src/main/resources/plugin.json`

```json
{
  "id": "my-plugin",
  "name": "My Plugin",
  "version": "1.0.0",
  "author": "Your Name",
  "api": 1,
  "main": "example.myplugin.MyPlugin",
  "permissions": []
}
```

### `src/main/java/example/myplugin/MyPlugin.java`

```java
package example.myplugin;

import mibsdk.api.MibPlugin;
import mibsdk.api.PluginContext;

public class MyPlugin extends MibPlugin {
    @Override
    protected void onEnable(PluginContext ctx) {
        ctx.log().info("my-plugin enabled");
    }
}
```

## What happens when you build

`./gradlew mibPluginJar` runs:

1. `compileJava` with JDK 17 and `--release 8`; Jabel accepts newer syntax.
2. `backport` to class-file version 48 (Java 1.4) with Retrolambda and Retrotranslator.
3. `classVersionCheck`: every class is v48.
4. `linkCheck`: every reference resolves against the car's class library.
5. `permissionScan`: no reflection or exec without `platform.unrestricted`, no undeclared permission,
   no API newer than the `"api"` you declared.
6. `mibPluginJar`: the checked classes plus `plugin.json` in `build/mibsdk/<id>.jar`, with a
   `<id>.jar.stamp` that records the checks and the SHA-256.

`./gradlew test` builds the jar first and runs your host tests against the real runtime and a fake car.

## Next

- [Project layout](project-layout.md): what each file and directory is.
- [Tutorial](tutorial.md): add a page, a slider, a status item and a toast.
- [Test your plugin](test-your-plugin.md): drive the fake car.
