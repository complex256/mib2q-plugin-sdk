package sample.crashy;

import mibsdk.api.IgnitionChanged;
import mibsdk.api.MibPlugin;
import mibsdk.api.PageSpec;
import mibsdk.api.PluginContext;
import mibsdk.api.StatusItem;

/**
 * Misbehaves on purpose, to show the SDK contains it. The mode comes from storage key {@code mode}
 * ({@code data/crashy.properties}), written by a test or by hand:
 * <ul>
 * <li>{@code throw-on-enable}: onEnable throws;
 * <li>{@code throw-on-event}: throws on every {@link IgnitionChanged};
 * <li>{@code hang-on-event}: sleeps forever in the {@link IgnitionChanged} listener;
 * <li>{@code busy-loop}: spins on its own scheduler task;
 * <li>{@code throw-on-timer}: a scheduler task every second that logs {@code tick} and throws;
 * <li>{@code hang-on-timer}: a scheduler task one second after enable that blocks forever;
 * <li>{@code ui-throw}: a page ({@code boom}, reached from Plugins › Crashy) whose action listener throws on every
 * press, and a status item rewritten in a tight loop (a burst of {@value #BURST} sets every {@value #BURST_MS} ms).
 * Three presses quarantine it: its open page turns into the stopped notice and its status item goes away.
 * </ul>
 * The timer modes need no ignition signal (the bench has none). Any other value: does nothing. The hangs and the
 * spin end only when the SDK interrupts the plugin's thread (it does once it has quarantined the plugin), so a
 * test run or a car does not keep burning a thread.
 */
public class CrashyPlugin extends MibPlugin {

    static final int BURST = 200;
    static final long BURST_MS = 100;

    private static volatile long spins;
    private long ticks;

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
            case "throw-on-timer":
                ctx.scheduler().every(1000, () -> {
                    ctx.log().info("tick");
                    throw new IllegalStateException("crashy: throw-on-timer");
                });
                break;
            case "hang-on-timer":
                ctx.scheduler().after(1000, () -> {
                    ctx.log().info("hanging");
                    try {
                        while (true) {
                            Thread.sleep(Long.MAX_VALUE);
                        }
                    } catch (InterruptedException e) {
                        // quarantined: the SDK interrupted this thread, so let it go
                    }
                });
                break;
            case "ui-throw":
                uiThrow(ctx);
                break;
            default:
                ctx.log().info("no crash mode set; idle");
        }
    }

    private void uiThrow(PluginContext ctx) {
        PageSpec spec = PageSpec.of("boom", "Crashy").item("boom", "Throw", null)
                .info("Each press throws; the third quarantines Crashy");
        ctx.ui().page(spec, action -> {
            throw new IllegalStateException("crashy: ui-throw " + action.itemId());
        });
        final StatusItem item = ctx.ui().statusItem("C0");
        ctx.scheduler().every(BURST_MS, () -> {
            for (int i = 0; i < BURST; i++) {
                item.set("C" + (ticks++ % 1000));
            }
        });
    }
}
