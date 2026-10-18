package sample.crashy;

import mibsdk.api.IgnitionChanged;
import mibsdk.api.MibPlugin;
import mibsdk.api.PluginContext;

/**
 * Misbehaves on purpose, to show the SDK contains it. The mode comes from storage key {@code mode}
 * ({@code data/crashy.properties}), written by a test or by hand:
 * <ul>
 * <li>{@code throw-on-enable}: onEnable throws;
 * <li>{@code throw-on-event}: throws on every {@link IgnitionChanged};
 * <li>{@code hang-on-event}: sleeps forever in the {@link IgnitionChanged} listener;
 * <li>{@code busy-loop}: spins on its own scheduler task.
 * </ul>
 * Any other value: does nothing. The hang and the spin end only when the SDK interrupts the plugin's thread
 * (it does once it has quarantined the plugin), so a test run or a car does not keep burning a thread.
 */
public class CrashyPlugin extends MibPlugin {

    private static volatile long spins;

    @Override
    protected void onEnable(PluginContext ctx) {
        String mode = ctx.storage().get("mode", "");
        ctx.log().info("mode=" + mode);
        switch (mode) {
            case "throw-on-enable":
                throw new IllegalStateException("crashy: throw-on-enable");
            case "throw-on-event":
                ctx.events().on(IgnitionChanged.class, e -> {
                    throw new IllegalStateException("crashy: throw-on-event");
                });
                break;
            case "hang-on-event":
                ctx.events().on(IgnitionChanged.class, e -> {
                    ctx.log().info("hanging");
                    while (true) {
                        Thread.sleep(Long.MAX_VALUE);
                    }
                });
                break;
            case "busy-loop":
                ctx.scheduler().after(0, () -> {
                    ctx.log().info("spinning");
                    while (!Thread.currentThread().isInterrupted()) {
                        spins++;
                    }
                });
                break;
            default:
                ctx.log().info("no crash mode set; idle");
        }
    }
}
