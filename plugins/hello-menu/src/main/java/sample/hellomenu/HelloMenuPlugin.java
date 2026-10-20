package sample.hellomenu;

import mibsdk.api.Features;
import mibsdk.api.MibPlugin;
import mibsdk.api.Page;
import mibsdk.api.PageSpec;
import mibsdk.api.PluginContext;
import mibsdk.api.Storage;
import mibsdk.api.UiAction;

/** Sample UI plugin: a fruit list page, reachable from the Plugins menu and, when the unit has the slot, the Car menu. */
public class HelloMenuPlugin extends MibPlugin {

    private static final String PAGE_ID = "fruits";

    private Storage storage;
    private Page page;

    @Override
    protected void onEnable(PluginContext ctx) {
        storage = ctx.storage();
        page = ctx.ui().page(spec(storage.get("last", null)), a -> onAction(a));
        if (ctx.has(Features.SLOT_CAR_MENU)) {
            ctx.ui().carMenuRow("Hello", page);
        }
    }

    private void onAction(UiAction action) {
        String id = action.itemId();
        if (id == null || name(id) == null) {
            return;
        }
        storage.put("last", id);
        page.update(spec(id));
    }

    private static PageSpec spec(String last) {
        String shown = last == null ? null : name(last);
        return PageSpec.of(PAGE_ID, "Hello")
                .item("apple", "Apple", null)
                .item("pear", "Pear", null)
                .item("plum", "Plum", null)
                .info("Selected: " + (shown == null ? "<none>" : shown));
    }

    private static String name(String id) {
        if ("apple".equals(id)) {
            return "Apple";
        }
        if ("pear".equals(id)) {
            return "Pear";
        }
        if ("plum".equals(id)) {
            return "Plum";
        }
        return null;
    }
}
