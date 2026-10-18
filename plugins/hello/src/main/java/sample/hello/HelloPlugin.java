package sample.hello;

import mibsdk.api.IgnitionChanged;
import mibsdk.api.MibPlugin;
import mibsdk.api.PluginContext;
import mibsdk.api.Storage;

/** Sample plugin: logs ignition changes and counts how often it was enabled (storage key {@code enables}). */
public class HelloPlugin extends MibPlugin {

    @Override
    protected void onEnable(PluginContext ctx) {
        Storage storage = ctx.storage();
        storage.put("enables", String.valueOf(enables(storage) + 1));
        ctx.events().on(IgnitionChanged.class, e -> ctx.log().info("ignition=" + e.isOn()));
        ctx.log().info("hello enabled");
    }

    private static int enables(Storage storage) {
        try {
            return Integer.parseInt(storage.get("enables", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
