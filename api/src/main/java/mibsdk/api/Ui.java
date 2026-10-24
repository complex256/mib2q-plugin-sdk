package mibsdk.api;

/**
 * Declarative plugin UI. Obtained from {@link PluginContext#ui()}. Text is never rejected for length: the
 * runtime truncates it. Exceeding a count limit throws {@link IllegalStateException}; a missing or invalid argument
 * throws {@link IllegalArgumentException}.
 *
 * <p>A missing feature is not an error. When the unit lacks the feature (the SDK UI is off this boot, or the unit has
 * no such slot) each method still returns a handle, but an inert one: {@code update}, {@code open}, {@code set} and
 * {@code cancel} are accepted and show nothing. Count limits and argument checks apply as usual, so a plugin that
 * works with the UI on works the same with it off. Use {@link PluginContext#has(String)} to tell the two apart.
 */
public interface Ui {

    /**
     * Registers a page under this plugin's Plugins-menu entry; actions arrive on the plugin's executor. Inert without
     * Features.UI_PAGES.
     */
    Page page(PageSpec spec, Listener<UiAction> onAction);

    /**
     * A row in the Car menu that opens {@code opens}, a live page from {@link #page}. Needs ui.slot.carmenu; inert
     * without Features.SLOT_CAR_MENU or when {@code opens} is inert.
     */
    Subscription carMenuRow(String label, Page opens);

    /**
     * One short status-line item; set(null|"") hides it. Needs ui.slot.statusbar; inert without SLOT_STATUS_BAR.
     *
     * <p>The line is shared: the runtime's own indicators come first and take at most half of it while there are
     * items, and the items share the rest fairly, so no plugin's item can crowd out another's. An item shorter than
     * an equal share is shown whole; a longer one may be cut to its share (with {@code ...} when the share is wide
     * enough), so put the most important text first. Items follow the user's order, then plugin id; when even three
     * characters each do not fit, the last items in that order are left out. The user may hide this plugin's item or
     * move it earlier or later from its page in the Plugins menu. None of that is visible to the plugin:
     * {@link StatusItem#set} keeps working and never fails because the item is hidden or cut.
     */
    StatusItem statusItem(String text);
}
