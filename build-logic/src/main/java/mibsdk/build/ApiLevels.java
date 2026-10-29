package mibsdk.build;

import java.util.HashMap;
import java.util.Map;

/**
 * The plugin api level each {@code mibsdk.api} member arrived in, for the members newer than api 1 (api 2 is the UI
 * and {@code Net}, api 3 icons, api 4 per-icon colour, api 5 native inputs, api 6 status-item text colour). Keyed by the
 * JVM reference a plugin class makes: owner internal name, member name and descriptor (a method's, or a field's for
 * {@code GETSTATIC}). Anything not listed is api 1. Compile-time constants ({@code static final} primitives and strings such as
 * {@code PageSpec.MAX_OPTIONS} or {@code Features.UI_INPUTS}) are inlined by the compiler, so they leave no reference
 * and are not listed.
 */
public final class ApiLevels {
    private ApiLevels() {}

    private static final String S = "Ljava/lang/String;";
    private static final String CONTEXT = "mibsdk/api/PluginContext";
    private static final String PAGE = "mibsdk/api/Page";
    private static final String STATUS_ITEM = "mibsdk/api/StatusItem";
    private static final String NET = "mibsdk/api/Net";
    private static final String STOCK_ICONS = "mibsdk/api/StockIcons";
    private static final String ICON = "Lmibsdk/api/Icon;";
    private static final String SUBSCRIPTION = "Lmibsdk/api/Subscription;";
    private static final String PAGE_SPEC = "mibsdk/api/PageSpec";
    private static final String ENTRY = "mibsdk/api/PageSpec$Entry";
    private static final String KIND = "mibsdk/api/PageSpec$Kind";
    private static final String UI = "mibsdk/api/Ui";
    private static final String UI_ACTION = "mibsdk/api/UiAction";
    private static final String UI_COLOR = "mibsdk/api/UiColor";
    private static final String R_SPEC = ")L" + PAGE_SPEC + ";";

    private static final class Level {
        final int api;
        final String label;

        Level(int api, String label) {
            this.api = api;
            this.label = label;
        }
    }

    private static final Map<String, Level> TABLE = new HashMap<String, Level>();

    static {
        // Api 2: pages, Car-menu rows, the status item, Net. Page and StatusItem extend Subscription (api 1), but a
        // plugin calls cancel() through its own type, so javac names Page / StatusItem as the owner.
        put(CONTEXT, "ui", "()Lmibsdk/api/Ui;", 2, "PluginContext.ui");
        put(CONTEXT, "net", "()L" + NET + ";", 2, "PluginContext.net");
        put(UI, "page", "(L" + PAGE_SPEC + ";Lmibsdk/api/Listener;)L" + PAGE + ";", 2, "Ui.page");
        put(UI, "carMenuRow", "(" + S + "L" + PAGE + ";)" + SUBSCRIPTION, 2, "Ui.carMenuRow(String,Page)");
        put(UI, "statusItem", "(" + S + ")L" + STATUS_ITEM + ";", 2, "Ui.statusItem(String)");
        put(PAGE, "id", "()" + S, 2, "Page.id");
        put(PAGE, "update", "(L" + PAGE_SPEC + ";)V", 2, "Page.update");
        put(PAGE, "open", "()V", 2, "Page.open");
        put(PAGE, "cancel", "()V", 2, "Page.cancel");
        put(PAGE_SPEC, "of", "(" + S + S + ")" + R_SPEC.substring(1), 2, "PageSpec.of");
        put(PAGE_SPEC, "item", "(" + S + S + S + R_SPEC, 2, "PageSpec.item(String,String,String)");
        put(PAGE_SPEC, "toggle", "(" + S + S + "Z" + R_SPEC, 2, "PageSpec.toggle");
        put(PAGE_SPEC, "info", "(" + S + R_SPEC, 2, "PageSpec.info");
        put(PAGE_SPEC, "link", "(" + S + S + "L" + PAGE + ";" + R_SPEC, 2, "PageSpec.link");
        put(PAGE_SPEC, "id", "()" + S, 2, "PageSpec.id");
        put(PAGE_SPEC, "title", "()" + S, 2, "PageSpec.title");
        put(PAGE_SPEC, "entries", "()Ljava/util/List;", 2, "PageSpec.entries");
        put(ENTRY, "kind", "()L" + KIND + ";", 2, "PageSpec.Entry.kind");
        put(ENTRY, "itemId", "()" + S, 2, "PageSpec.Entry.itemId");
        put(ENTRY, "label", "()" + S, 2, "PageSpec.Entry.label");
        put(ENTRY, "value", "()" + S, 2, "PageSpec.Entry.value");
        put(ENTRY, "on", "()Z", 2, "PageSpec.Entry.on");
        put(ENTRY, "target", "()L" + PAGE + ";", 2, "PageSpec.Entry.target");
        for (String k : new String[] {"ITEM", "TOGGLE", "INFO", "LINK"}) {
            put(KIND, k, "L" + KIND + ";", 2, "PageSpec.Kind." + k);
        }
        put(KIND, "values", "()[L" + KIND + ";", 2, "PageSpec.Kind.values");
        put(KIND, "valueOf", "(" + S + ")L" + KIND + ";", 2, "PageSpec.Kind.valueOf");
        put(UI_ACTION, "<init>", "(" + S + S + "Ljava/lang/Boolean;)V", 2, "new UiAction(String,String,Boolean)");
        put(UI_ACTION, "pageId", "()" + S, 2, "UiAction.pageId");
        put(UI_ACTION, "itemId", "()" + S, 2, "UiAction.itemId");
        put(UI_ACTION, "requestedOn", "()Ljava/lang/Boolean;", 2, "UiAction.requestedOn");
        put(UI_ACTION, "toString", "()" + S, 2, "UiAction.toString");
        put(STATUS_ITEM, "set", "(" + S + ")V", 2, "StatusItem.set(String)");
        put(STATUS_ITEM, "cancel", "()V", 2, "StatusItem.cancel");
        put(NET, "query", "(" + S + "I" + S + "I)" + S, 2, "Net.query");

        // Api 3: icons.
        put(UI, "carMenuRow", "(" + S + "L" + PAGE + ";" + ICON + ")" + SUBSCRIPTION, 3, "Ui.carMenuRow(String,Page,Icon)");
        put(UI, "statusItem", "(" + S + ICON + ")L" + STATUS_ITEM + ";", 3, "Ui.statusItem(String,Icon)");
        put(UI, "icon", "(" + S + ")" + ICON, 3, "Ui.icon(String)");
        put(UI, "stockIcon", "(" + S + ")" + ICON, 3, "Ui.stockIcon");
        put(STATUS_ITEM, "set", "(" + S + ICON + ")V", 3, "StatusItem.set(String,Icon)");
        put(STOCK_ICONS, "isKnown", "(" + S + ")Z", 3, "StockIcons.isKnown");

        // Api 4: per-icon colour.
        put(UI, "icon", "(" + S + "I)Lmibsdk/api/Icon;", 4, "Ui.icon(String,int)");

        // Api 5: native inputs.
        put(PAGE_SPEC, "choice", "(" + S + S + "[" + S + "I" + R_SPEC, 5, "PageSpec.choice");
        put(PAGE_SPEC, "dropdown", "(" + S + S + "[" + S + "I" + R_SPEC, 5, "PageSpec.dropdown");
        put(PAGE_SPEC, "slider", "(" + S + S + "IIIIZ" + R_SPEC, 5, "PageSpec.slider");
        put(PAGE_SPEC, "progress", "(" + S + S + "I" + R_SPEC, 5, "PageSpec.progress");
        put(PAGE_SPEC, "item", "(" + S + S + S + "Lmibsdk/api/Icon;" + R_SPEC, 5, "PageSpec.item(String,String,String,Icon)");
        put(PAGE_SPEC, "enabled", "(" + S + "Z" + R_SPEC, 5, "PageSpec.enabled");
        put(ENTRY, "options", "()[" + S, 5, "PageSpec.Entry.options");
        put(ENTRY, "number", "()I", 5, "PageSpec.Entry.number");
        put(ENTRY, "min", "()I", 5, "PageSpec.Entry.min");
        put(ENTRY, "max", "()I", 5, "PageSpec.Entry.max");
        put(ENTRY, "step", "()I", 5, "PageSpec.Entry.step");
        put(ENTRY, "centred", "()Z", 5, "PageSpec.Entry.centred");
        put(ENTRY, "icon", "()Lmibsdk/api/Icon;", 5, "PageSpec.Entry.icon");
        put(ENTRY, "enabled", "()Z", 5, "PageSpec.Entry.enabled");
        for (String k : new String[] {"CHOICE", "DROPDOWN", "SLIDER", "PROGRESS"}) {
            put(KIND, k, "L" + KIND + ";", 5, "PageSpec.Kind." + k);
        }
        put(UI, "confirm", "(" + S + S + S + S + "Lmibsdk/api/Listener;)Lmibsdk/api/Subscription;", 5, "Ui.confirm");
        put(UI, "toast", "(" + S + "Lmibsdk/api/Icon;)V", 5, "Ui.toast");
        put(UI, "speller", "(" + S + S + "ILmibsdk/api/Listener;)Lmibsdk/api/Subscription;", 5, "Ui.speller");
        put(UI_ACTION, "value", "()Ljava/lang/Integer;", 5, "UiAction.value");
        put(UI_ACTION, "text", "()" + S, 5, "UiAction.text");
        put(UI_ACTION, "<init>", "(" + S + S + "Ljava/lang/Boolean;Ljava/lang/Integer;" + S + ")V", 5,
                "new UiAction(String,String,Boolean,Integer,String)");

        // Api 6: a status item's text colour in the built-in bar.
        put(STATUS_ITEM, "setColour", "(Lmibsdk/api/UiColor;)V", 6, "StatusItem.setColour");
        put(STATUS_ITEM, "visibleIn", "([I)V", 6, "StatusItem.visibleIn");
        put(STATUS_ITEM, "setIcon", "(Lmibsdk/api/Icon;)V", 6, "StatusItem.setIcon");
        put(UI_COLOR, "rgb", "(III)Lmibsdk/api/UiColor;", 6, "UiColor.rgb");
        put(UI_COLOR, "rgba", "(IIII)Lmibsdk/api/UiColor;", 6, "UiColor.rgba");
        put(UI_COLOR, "of", "(I)Lmibsdk/api/UiColor;", 6, "UiColor.of");
        put(UI_COLOR, "argb", "()I", 6, "UiColor.argb");

        // Api 7: the core Wi-Fi uplink and the wider secret text input.
        put(CONTEXT, "wifi", "()Lmibsdk/api/Wifi;", 7, "PluginContext.wifi");
        put(UI, "textInput", "(" + S + S + "IZLmibsdk/api/Listener;)Lmibsdk/api/Subscription;", 7, "Ui.textInput");
    }

    private static void put(String owner, String name, String desc, int api, String label) {
        TABLE.put(owner + "." + name + desc, new Level(api, label));
    }

    /** The api level {@code owner.name desc} arrived in; 1 if it is not listed (api 1, or not SDK api at all). */
    public static int since(String owner, String name, String desc) {
        Level l = TABLE.get(owner + "." + name + desc);
        return l == null ? 1 : l.api;
    }

    /** The member as reports name it ({@code PageSpec.choice}); null if it is not listed. */
    static String label(String owner, String name, String desc) {
        Level l = TABLE.get(owner + "." + name + desc);
        return l == null ? null : l.label;
    }
}
