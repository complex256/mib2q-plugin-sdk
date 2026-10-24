package sample.hellomenu;

import mibsdk.api.MibPlugin;
import mibsdk.api.Page;
import mibsdk.api.PageSpec;
import mibsdk.api.PluginContext;
import mibsdk.api.Storage;
import mibsdk.api.UiAction;

/**
 * Sample UI plugin: a fruit list page, reachable from the Plugins menu and, when the unit has the slot, the Car menu.
 * The manifest declares {@code ui.slot.carmenu}; permissions are all-or-nothing, so a running plugin holds it. The last selection is kept in storage ({@code last}) and restored on enable.
 */
public class HelloMenuPlugin extends MibPlugin {

    private static final String PAGE_ID = "fruits";
    /** Item id, label. */
    private static final String[][] FRUITS = {{"apple", "Apple"}, {"pear", "Pear"}, {"plum", "Plum"}};

    private Storage storage;
    private Page page;

    @Override
    protected void onEnable(PluginContext ctx) {
        storage = ctx.storage();
        // With the SDK UI off (or no Car slot) these handles are inert: the plugin still runs, nothing is shown.
        page = ctx.ui().page(spec(storage.get("last", null)), this::onAction);
        ctx.ui().carMenuRow("Hello", page);
    }

    private void onAction(UiAction action) {
        String id = action.itemId();
        if (label(id) == null) {
            return;
        }
        storage.put("last", id);
        page.update(spec(id));
    }

    private static PageSpec spec(String last) {
        PageSpec spec = PageSpec.of(PAGE_ID, "Hello");
        for (String[] fruit : FRUITS) {
            spec = spec.item(fruit[0], fruit[1], null);
        }
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
