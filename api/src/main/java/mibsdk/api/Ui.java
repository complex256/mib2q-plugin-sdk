package mibsdk.api;

/**
 * Declarative plugin UI. Obtained from {@link PluginContext#ui()}. Text is never rejected for length: the
 * runtime truncates it. Exceeding a count limit throws {@link IllegalStateException}.
 */
public interface Ui {

    /** Registers a page under this plugin's Plugins-menu entry; actions arrive on the plugin's executor. */
    Page page(PageSpec spec, Listener<UiAction> onAction);

    /** A row in the Car menu that opens {@code opens}. Needs ui.slot.carmenu and Features.SLOT_CAR_MENU. */
    Subscription carMenuRow(String label, Page opens);

    /** One short status-line item; set(null|"") hides it. Needs ui.slot.statusbar and Features.SLOT_STATUS_BAR. */
    StatusItem statusItem(String text);
}
