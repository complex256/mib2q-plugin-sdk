package sample.hellomenu;

import mibsdk.api.Icon;
import mibsdk.api.MibPlugin;
import mibsdk.api.Page;
import mibsdk.api.PageSpec;
import mibsdk.api.PluginContext;
import mibsdk.api.StatusItem;
import mibsdk.api.StockIcons;
import mibsdk.api.Storage;
import mibsdk.api.Subscription;
import mibsdk.api.Ui;
import mibsdk.api.UiAction;

/**
 * Sample UI plugin: a fruit list page, reachable from the Plugins menu and, when the unit has the slot, the Car menu.
 * The manifest declares {@code ui.slot.carmenu} and {@code ui.slot.statusbar} (the status item), and the icons {@code hello} (72x72) and {@code hello-20} (20x20).
 * "Next icon" swaps the Car row's icon between {@code hello} and a stock star (it shows when the Car screen next opens);
 * "Next status icon" cycles the status item's icon hello-20, star, dot. The status item draws hello-20 in its own colours
 * (half white, half blue; api 4) by default, and "Status icon colour" flips it to tinted and back; the stock icons stay
 * tinted. Permissions are all-or-nothing, so a running plugin holds it. The last selection ({@code last}) and the status
 * icon colour ({@code colour}) are kept in storage and restored on enable.
 */
public class HelloMenuPlugin extends MibPlugin {

    private static final String PAGE_ID = "fruits";
    /** Item id, label. */
    private static final String[][] FRUITS = {{"apple", "Apple"}, {"pear", "Pear"}, {"plum", "Plum"}};

    private Storage storage;
    private Ui ui;
    private Page page;
    private Subscription row;
    private boolean rowStar;
    private StatusItem status;
    private int statusIcon;
    private int statusColour;

    @Override
    protected void onEnable(PluginContext ctx) {
        storage = ctx.storage();
        // With the SDK UI off (or no Car slot) these handles are inert: the plugin still runs, nothing is shown.
        ui = ctx.ui();
        statusColour = "tinted".equals(storage.get("colour", null)) ? Icon.TINTED : Icon.OWN_COLOURS;
        page = ui.page(spec(storage.get("last", null)), this::onAction);
        row = ui.carMenuRow("Hello", page, ui.icon("hello"));
        status = ui.statusItem("Hello", statusIcon(0));
    }

    private void onAction(UiAction action) {
        String id = action.itemId();
        if ("next-icon".equals(id)) {
            rowStar = !rowStar;
            row.cancel(); // the Car screen reads its rows when it opens: the new icon shows at the next Car connect
            row = ui.carMenuRow("Hello", page, rowStar ? ui.stockIcon(StockIcons.STAR) : ui.icon("hello"));
            return;
        }
        if ("next-status-icon".equals(id)) {
            statusIcon = (statusIcon + 1) % 3;
            status.set("Hello", statusIcon(statusIcon));
            return;
        }
        if ("status-colour".equals(id)) {
            statusColour = statusColour == Icon.OWN_COLOURS ? Icon.TINTED : Icon.OWN_COLOURS;
            storage.put("colour", statusColour == Icon.OWN_COLOURS ? "own" : "tinted");
            status.set("Hello", statusIcon(statusIcon));
            return;
        }
        if (label(id) == null) {
            return;
        }
        storage.put("last", id);
        page.update(spec(id));
    }

    private Icon statusIcon(int n) {
        return n == 0 ? ui.icon("hello-20", statusColour) : ui.stockIcon(n == 1 ? StockIcons.STAR : StockIcons.DOT);
    }

    private static PageSpec spec(String last) {
        PageSpec spec = PageSpec.of(PAGE_ID, "Hello");
        for (String[] fruit : FRUITS) {
            spec = spec.item(fruit[0], fruit[1], null);
        }
        spec = spec.item("next-icon", "Next icon", null).item("next-status-icon", "Next status icon", null)
                .item("status-colour", "Status icon colour", null);
        String shown = label(last);
        return spec.info("Selected: " + (shown == null ? "<none>" : shown));
    }

    private static String label(String id) {
        for (String[] fruit : FRUITS) {
            if (fruit[0].equals(id)) {
                return fruit[1];
            }
        }
        return null;
    }
}
