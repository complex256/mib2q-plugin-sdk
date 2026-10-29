# My Plugin

A standalone [mib2q plugin SDK](https://complex256.github.io/mib2q-plugin-sdk/) plugin.

## Build

```sh
./gradlew test
./gradlew mibPluginJar
```

Output:

```text
build/mibsdk/my-plugin.jar
build/mibsdk/my-plugin.jar.stamp
```

## Rename it

| File | Change |
|---|---|
| `src/main/resources/plugin.json` | `id`, `name`, `main`, `permissions` |
| `src/main/java/example/myplugin/MyPlugin.java` | package and class name |
| `src/test/java/example/myplugin/MyPluginTest.java` | package and class name |

The id must be 2–40 characters of `a-z`, `0-9` and `-`, starting with a letter. `mibsdk` is reserved.

## Add a page and a Car row

```java
@Override
protected void onEnable(PluginContext ctx) {
    Page page = ctx.ui().page(
            PageSpec.of("main", "My plugin")
                    .item("hello", "Say hello", null),
            action -> ctx.log().info("hello"));
    ctx.ui().carMenuRow("My plugin", page, ctx.ui().stockIcon(StockIcons.STAR));
}
```

Add the permissions the page and row need:

```json
"permissions": ["ui.slot.carmenu"]
```

## Add an icon

1. Put a 72×72 8-bit RGBA PNG at `src/main/icons/my-icon.png`.
2. Add `"my-icon"` to `"icons"` in `plugin.json` and set `"api": 3`.

## Package

```sh
./gradlew mibpkgPackage -Pmibsdk.mibpkg=/path/to/mibpkg
```

Output: `build/mibsdk/my-plugin-1.0.0.mpk`. Install it with mibpkg and approve the permissions.

## Documentation

- [Getting started](https://complex256.github.io/mib2q-plugin-sdk/getting-started/)
- [Tutorial](https://complex256.github.io/mib2q-plugin-sdk/getting-started/tutorial/)
- [plugin.json](https://complex256.github.io/mib2q-plugin-sdk/reference/plugin-manifest/)
- [Java API](https://complex256.github.io/mib2q-plugin-sdk/api/)
