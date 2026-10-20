package sample.daemonstatus;

import java.util.Map;
import mibsdk.api.Features;
import mibsdk.api.MibPlugin;
import mibsdk.api.Net;
import mibsdk.api.Page;
import mibsdk.api.PageSpec;
import mibsdk.api.Permissions;
import mibsdk.api.Scheduler;
import mibsdk.api.PluginContext;
import mibsdk.api.StatusItem;
import mibsdk.api.Storage;
import mibsdk.api.UiAction;

/**
 * Reference plugin: tailscale (tsstatus, port 19850) and carmqtt (port 19851) state as a status-line item and a
 * detail page, polled over loopback. "(stale)" after the last-publish age (over 5 minutes) is informational only. Ports can be overridden with storage keys {@code ts.port} / {@code mq.port}.
 */
public class DaemonStatusPlugin extends MibPlugin {

    private static final String PAGE_ID = "status";
    private static final String HOST = "127.0.0.1";
    private static final int TS_PORT = 19850;
    private static final int MQ_PORT = 19851;
    /** Two sequential queries must stay under the 5000 ms slow-callback limit. */
    private static final int TIMEOUT_MS = 2000;
    private static final long POLL_MS = 15000;
    /** A carmqtt publish older than this is flagged stale: {@code mqtt} is only the last known state. */
    private static final int STALE_S = 300;

    private Net net;
    private Scheduler scheduler;
    private volatile boolean queued;
    private Page page;
    private StatusItem item;
    private int tsPort;
    private int mqPort;

    @Override
    protected void onEnable(PluginContext ctx) {
        Storage storage = ctx.storage();
        tsPort = port(storage.get("ts.port", null), TS_PORT);
        mqPort = port(storage.get("mq.port", null), MQ_PORT);
        // ctx.net() throws without the grant, so say so on the page instead.
        if (!ctx.granted(Permissions.NETWORK)) {
            page = ctx.ui().page(PageSpec.of(PAGE_ID, "Daemons").info("Network permission not granted"), a -> { });
            return;
        }
        net = ctx.net();
        scheduler = ctx.scheduler();
        page = ctx.ui().page(spec(null, null), this::onAction);
        if (ctx.has(Features.SLOT_STATUS_BAR) && ctx.granted(Permissions.UI_SLOT_STATUS_BAR)) {
            item = ctx.ui().statusItem(null);
        }
        scheduler.after(0, this::refresh);
        scheduler.every(POLL_MS, this::refresh);
    }

    private void onAction(UiAction action) {
        // Each poll blocks the executor for up to 2 x TIMEOUT_MS: mashing Refresh queues only one.
        if ("refresh".equals(action.itemId()) && !queued) {
            queued = true;
            scheduler.after(0, this::refresh);
        }
    }

    private void refresh() {
        queued = false;
        Map<String, String> ts = query(tsPort, "tailscale");
        Map<String, String> mq = query(mqPort, "carmqtt");
        page.update(spec(ts, mq));
        if (item != null) {
            item.set("TS " + tsWord(ts) + " MQ " + mqWord(mq));
        }
    }

    /** The daemon's parsed reply; empty when it is unreachable, silent, another daemon or an unknown version. */
    private Map<String, String> query(int port, String daemon) {
        try {
            Map<String, String> m = StatusLines.parse(net.query(HOST, port, null, TIMEOUT_MS));
            return daemon.equals(m.get("daemon")) ? m : StatusLines.parse(null);
        } catch (java.io.IOException e) {
            return StatusLines.parse(null);
        }
    }

    private static String tsWord(Map<String, String> ts) {
        return ts.isEmpty() ? "?" : "Running".equals(ts.get("state")) ? "up" : "dn";
    }

    private static String mqWord(Map<String, String> mq) {
        return mq.isEmpty() ? "?" : "1".equals(mq.get("mqtt")) ? "up" : "dn";
    }

    private static PageSpec spec(Map<String, String> ts, Map<String, String> mq) {
        PageSpec spec = PageSpec.of(PAGE_ID, "Daemons");
        if (ts == null || ts.isEmpty()) {
            spec = spec.info("Tailscale: ?");
        } else {
            String ip = get(ts, "ip");
            spec = spec.info("Tailscale: " + get(ts, "state") + (ip.length() == 0 ? "" : " " + ip))
                    .info("Tailnet: " + get(ts, "tailnet"))
                    .info("Peers: " + num(ts, "peers_online") + "/" + num(ts, "peers"));
        }
        if (mq == null || mq.isEmpty()) {
            spec = spec.info("carmqtt: ?");
        } else {
            int age = intOr(mq.get("last_publish_age_s"), Integer.MIN_VALUE);
            spec = spec.info("carmqtt: MQTT " + onOff(mq.get("mqtt")) + ", ExLAP " + onOff(mq.get("exlap")))
                    .info("Last publish: " + publishText(age));
            String err = get(mq, "exlap_err");
            if (err.length() > 0) {
                spec = spec.info("Error: " + err);
            }
        }
        return spec.item("refresh", "Refresh now", null);
    }

    /** "(stale)" is informational only: it does not change the status item. */
    private static String publishText(int age) {
        if (age == Integer.MIN_VALUE) {
            return "?";
        }
        return age < 0 ? "never" : age + " s ago" + (age > STALE_S ? " (stale)" : "");
    }

    /** A non-negative integer field, or "?" when absent or garbled. */
    private static String num(Map<String, String> m, String key) {
        int n = intOr(m.get(key), -1);
        return n < 0 ? "?" : String.valueOf(n);
    }

    private static String get(Map<String, String> m, String key) {
        String v = m.get(key);
        return v == null ? "" : v;
    }

    private static String onOff(String flag) {
        return "1".equals(flag) ? "on" : "off";
    }

    private static int intOr(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int port(String s, int def) {
        int p = intOr(s, def);
        return p > 0 && p < 65536 ? p : def;
    }
}
