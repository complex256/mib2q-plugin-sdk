package mibsdk.adapter.hqc.ui.icon;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import mibsdk.adapter.hqc.ui.page.PageScreens;
import mibsdk.adapter.hqc.ui.page.UiApplier;
import mibsdk.platform.ui.UiIcon;

/**
 * The image ids of the page items' icons (F28): {@code UiIcon -> id}. Built on {@code mibsdk-ui} by
 * {@link UiApplier#assignItemIcons} (the only place item icon ids are given), published to {@link PageScreens} through
 * a volatile field. Immutable, so stock-thread code reads it without a lock and never assigns an id.
 */
public final class ItemIcons {

    /** No item icons. */
    public static final ItemIcons NONE = new ItemIcons(Collections.<UiIcon, Integer>emptyMap());

    private final Map<UiIcon, Integer> ids;

    /** {@code ids} is copied. */
    public ItemIcons(Map<UiIcon, Integer> ids) {
        this.ids = Collections.unmodifiableMap(new HashMap<UiIcon, Integer>(ids));
    }

    /** The image id of {@code icon}, or -1 if it has none. */
    public int id(UiIcon icon) {
        Integer i = icon == null ? null : ids.get(icon);
        return i == null ? -1 : i.intValue();
    }

    public int size() {
        return ids.size();
    }

    public boolean equals(Object o) {
        return o instanceof ItemIcons && ((ItemIcons) o).ids.equals(ids);
    }

    public int hashCode() {
        return ids.hashCode();
    }

    public String toString() {
        return "ItemIcons" + ids;
    }
}
