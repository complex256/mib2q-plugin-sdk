package mibsdk.build;

import java.util.HashMap;
import java.util.Map;

/**
 * The plugin api level each {@code mibsdk.api} member arrived in, for the members newer than api 1. Keyed by the
 * JVM reference a plugin class makes: owner internal name, member name and descriptor (a method's, or a field's for
 * {@code GETSTATIC}). Compile-time constants ({@code static final} primitives and strings such as
 * {@code PageSpec.MAX_OPTIONS} or {@code Features.UI_INPUTS}) are inlined by the compiler, so they leave no reference
 * and are not listed.
 */
public final class ApiLevels {
    private ApiLevels() {}

    private static final String S = "Ljava/lang/String;";
    private static final String PAGE_SPEC = "mibsdk/api/PageSpec";
    private static final String ENTRY = "mibsdk/api/PageSpec$Entry";
    private static final String KIND = "mibsdk/api/PageSpec$Kind";
    private static final String UI = "mibsdk/api/Ui";
    private static final String UI_ACTION = "mibsdk/api/UiAction";
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
        put(UI, "icon", "(" + S + "I)Lmibsdk/api/Icon;", 4, "Ui.icon(String,int)");

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
