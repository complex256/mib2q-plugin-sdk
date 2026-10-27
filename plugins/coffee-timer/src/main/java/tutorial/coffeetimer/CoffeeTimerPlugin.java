package tutorial.coffeetimer;

import mibsdk.api.Icon;
import mibsdk.api.Log;
import mibsdk.api.MibPlugin;
import mibsdk.api.Page;
import mibsdk.api.PageSpec;
import mibsdk.api.PluginContext;
import mibsdk.api.Scheduler;
import mibsdk.api.StatusItem;
import mibsdk.api.Storage;
import mibsdk.api.Subscription;
import mibsdk.api.Ui;
import mibsdk.api.UiAction;

/**
 * A brew timer: a Car-drawer row opens the "Coffee timer" page, where you set the brew time (a slider) and whether a
 * toast pops up when it is done (a toggle). While a timer runs, the status line counts down; when it ends it says
 * "Coffee ready" until you dismiss it. The brew time and the toggle are kept in storage.
 */
public class CoffeeTimerPlugin extends MibPlugin {

    static final String PAGE_ID = "timer";
    static final int MIN_MINUTES = 1;
    static final int MAX_MINUTES = 10;
    static final int DEFAULT_MINUTES = 4;
    static final long MINUTE_MS = 60_000L;

    private Log log;
    private Storage storage;
    private Scheduler scheduler;
    private Ui ui;
    private Page page;
    private StatusItem status;
    private Icon cupSmall;

    private int minutes;           // the brew time, stored as "minutes"
    private boolean toastWhenReady; // stored as "toast" = on|off
    private int left;              // minutes left; 0 when no timer runs
    private boolean ready;         // the timer ended and nobody dismissed it yet
    private Subscription tick;     // the once-a-minute task while a timer runs

    @Override
    protected void onEnable(PluginContext ctx) {
        log = ctx.log();
        storage = ctx.storage();
        scheduler = ctx.scheduler();
        ui = ctx.ui();

        minutes = storedMinutes();
        toastWhenReady = !"off".equals(storage.get("toast", "on"));

        cupSmall = ui.icon("cup-20");
        page = ui.page(spec(), this::onAction);
        ui.carMenuRow("Coffee", page, ui.icon("cup"));
        status = ui.statusItem(null, cupSmall); // hidden until a timer runs
        log.info("enabled, brew time " + minutes + " min");
    }

    @Override
    protected void onDisable() {
        // The runtime has already cancelled the timer, the page, the row and the status item.
        log.info("disabled");
    }

    private void onAction(UiAction a) {
        switch (a.itemId()) {
            case "start" -> start();
            case "stop" -> stop();
            case "dismiss" -> {
                ready = false;
                status.set(null);
            }
            case "toast" -> {
                toastWhenReady = a.requestedOn().booleanValue();
                storage.put("toast", toastWhenReady ? "on" : "off");
            }
            case "minutes" -> {
                minutes = a.value().intValue();
                storage.put("minutes", String.valueOf(minutes));
            }
            default -> log.warn("unknown item " + a.itemId());
        }
        page.update(spec());
    }

    private void start() {
        cancelTick();
        ready = false;
        left = minutes;
        tick = scheduler.every(MINUTE_MS, this::onMinute);
        status.set("Coffee " + left + "m", cupSmall);
        log.info("brewing for " + minutes + " min");
    }

    private void stop() {
        cancelTick();
        left = 0;
        status.set(null);
        log.info("stopped");
    }

    private void onMinute() {
        left--;
        if (left > 0) {
            status.set("Coffee " + left + "m", cupSmall);
        } else {
            cancelTick();
            ready = true;
            status.set("Coffee ready", cupSmall);
            if (toastWhenReady) {
                ui.toast("Coffee is ready", cupSmall);
            }
            log.info("ready");
        }
        page.update(spec());
    }

    private void cancelTick() {
        if (tick != null) {
            tick.cancel();
            tick = null;
        }
    }

    private PageSpec spec() {
        PageSpec s = PageSpec.of(PAGE_ID, "Coffee timer");
        s = left > 0 ? s.item("stop", "Stop timer", left + " min left") : s.item("start", "Start timer", minutes + " min");
        if (ready) {
            s = s.item("dismiss", "Dismiss", null);
        }
        return s.toggle("toast", "Pop up when ready", toastWhenReady)
                .slider("minutes", "Brew time", MIN_MINUTES, MAX_MINUTES, 1, minutes, false)
                .info(ready ? "Your coffee is ready." : left > 0 ? "Brewing..." : "Set a brew time, then Start.");
    }

    /** The stored brew time, or the default when none is stored or it is out of range. */
    private int storedMinutes() {
        try {
            int m = Integer.parseInt(storage.get("minutes", String.valueOf(DEFAULT_MINUTES)));
            return m >= MIN_MINUTES && m <= MAX_MINUTES ? m : DEFAULT_MINUTES;
        } catch (NumberFormatException e) {
            return DEFAULT_MINUTES;
        }
    }
}
