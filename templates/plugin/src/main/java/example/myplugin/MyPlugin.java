package example.myplugin;

import mibsdk.api.MibPlugin;
import mibsdk.api.PluginContext;

/** The smallest plugin: it logs one line when the runtime enables it. */
public class MyPlugin extends MibPlugin {

    @Override
    protected void onEnable(PluginContext ctx) {
        ctx.log().info("my-plugin enabled");
    }
}
