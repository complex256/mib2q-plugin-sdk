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

    /** The largest Car-row icon, in pixels, either way. A package's icons are at most this size. */
    int ROW_ICON_MAX_PX = 72;

    /**
     * The longest {@link #confirm} text; the runtime truncates longer text. Needs api 5.
     *
     * @since api 5
     */
    int MAX_CONFIRM_TEXT = 120;

    /**
     * The longest {@link #toast} text; the runtime truncates longer text. Needs api 5.
     *
     * @since api 5
     */
    int MAX_TOAST = 20;

    /**
     * How long a {@link #toast} shows, in milliseconds. Needs api 5.
     *
     * @since api 5
     */
    int TOAST_MS = 3000;

    /**
     * The largest {@link #speller} {@code maxLen}. Needs api 5.
     *
     * @since api 5
     */
    int MAX_SPELLER = 32;

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
     * {@link #carMenuRow(String, Page)} with an icon; a null icon means none. The icon must come from this plugin's
     * {@link #icon} or {@link #stockIcon}. Without Features.UI_ICONS the row shows without it. The Car screen reads its
     * rows when it opens, so an icon change shows the next time the Car screen opens.
     *
     * @since api 3
     */
    Subscription carMenuRow(String label, Page opens, Icon icon);

    /**
     * One short status-line item; set(null|"") hides it. Needs ui.slot.statusbar; inert without SLOT_STATUS_BAR.
     *
     * <p>The line is shared: the runtime's own indicators come first. The safety ones ({@code SAFE},
     * {@code PDC R OFF}) always show; the others give way to plugin items. The items share what is left fairly, so no
     * plugin's item can crowd out another's. An item shorter than
     * an equal share is shown whole; a longer one may be cut to its share (with {@code ...} when the share is wide
     * enough), so put the most important text first. Items follow the user's order, then plugin id; when even three
     * characters each do not fit, the last items in that order are left out (all of them if none fits). The user may hide this plugin's item or
     * move it earlier or later from its page in the Plugins menu. None of that is visible to the plugin:
     * {@link StatusItem#set} keeps working and never fails because the item is hidden or cut.
     */
    StatusItem statusItem(String text);

    /**
     * {@link #statusItem(String)} with an icon; a null icon means none. An installed icon larger than
     * {@link StatusItem#ICON_MAX_PX} throws {@link IllegalArgumentException}. Without Features.UI_ICONS the item shows
     * without it; the checks are the same either way.
     *
     * @since api 3
     */
    StatusItem statusItem(String text, Icon icon);

    /**
     * An icon this plugin declared by name in its manifest's {@code "icons"} (needs {@code "api": 3}); the package
     * carries it as {@code icons/<name>.png}, and mibpkg installs it as {@code icons/<id>/<sha16>.png} (the first 16 hex
     * digits of its SHA-256). An undeclared name throws {@link IllegalArgumentException}. Icons are
     * static per installed package version: a new picture needs a new package. A declared icon that is not installed
     * (the jar was pushed without installing its package) gives an inert icon: rows and items show without it, and
     * the runtime logs one WARN. The icon is {@link Icon#TINTED}: {@code icon(name)} is {@code icon(name, Icon.TINTED)}.
     *
     * @since api 3
     */
    Icon icon(String name);

    /**
     * {@link #icon(String)} drawn either {@link Icon#TINTED} or in its {@link Icon#OWN_COLOURS}; any other
     * {@code colour} throws {@link IllegalArgumentException}. Needs api 4. Status items honour it. Car rows ignore it:
     * the drawer draws plugin row icons in their own colours, so ship a white glyph for the stock look. Its
     * selected-row tint (the current screen's row, multiplied by the app colour) never applies to plugin rows on this
     * HMI (observed on the bench, C2): a plugin page carries no drawer, so a plugin row is never the drawer's selected row.
     *
     * @since api 4
     */
    Icon icon(String name, int colour);

    /**
     * One of the SDK's built-in icons, named by a {@link StockIcons} constant; any other name throws
     * {@link IllegalArgumentException}. Always {@link Icon#TINTED}: the built-ins are white glyphs, so in their own
     * colours they would only be white.
     *
     * @since api 3
     */
    Icon stockIcon(String name);

    /**
     * Asks a yes/no question over this plugin's open page. It opens only while one of this plugin's pages is the open
     * page. {@code text} lines break at {@code '\n'} (at most 3 lines, {@link #MAX_CONFIRM_TEXT} characters); null
     * {@code yes}/{@code no} mean "Yes"/"No". A null listener gets no answer.
     *
     * <p>Exactly once: a call with a listener is answered exactly once on the plugin's executor, unless
     * {@link Subscription#cancel()} withdraws it first. A call made while the plugin is stopping comes back cancelled
     * and is never answered. {@link UiAction#value()} is 1 for Yes, and 0 for No, BACK, dismissed (its page closed),
     * not over this plugin's open page, another confirm or speller already up, or inert (without
     * {@link Features#UI_INPUTS}). Needs api 5.
     *
     * @since api 5
     */
    Subscription confirm(String title, String text, String yes, String no, Listener<UiAction> onAnswer);

    /**
     * Shows {@code text} (at most {@link #MAX_TOAST} characters) with an optional icon for {@link #TOAST_MS} in a short
     * popup at the top of the screen. Needs ui.slot.statusbar (else {@link PermissionDeniedException}, as {@link #statusItem}); the icon
     * follows the status-icon rule (one of this plugin's icons, at most {@link StatusItem#ICON_MAX_PX}, else
     * {@link IllegalArgumentException}).
     * Inert without {@link Features#UI_INPUTS}. Needs api 5.
     *
     * @since api 5
     */
    void toast(String text, Icon icon);

    /**
     * EXPERIMENTAL. Opens the HMI speller (an on-screen keyboard) over this plugin's open page, starting from
     * {@code initial}; {@code maxLen} is 1..{@link #MAX_SPELLER}, else {@link IllegalArgumentException}. A null
     * listener gets no answer. The speller has no OK key: BACK leaves the speller and is "done", so {@code onDone} gets
     * {@link UiAction#text()} = the typed text. A page may offer its own Save row.
     *
     * <p>Exactly once: a call with a listener is answered exactly once on the plugin's executor, unless
     * {@link Subscription#cancel()} withdraws it first. A call made while the plugin is stopping comes back cancelled
     * and is never answered. {@link UiAction#text()} is the typed text (at most {@code maxLen} characters) when the
     * user leaves a speller they saw: BACK, or another screen (a stock one) taking over. It is null when the speller
     * never showed (inert without {@link Features#UI_INPUTS}, not over this plugin's page, another confirm or speller
     * already up, or its screen could not be shown), or when it was dismissed because its page closed, including when
     * this plugin itself opens another page or removes the page under it while the speller is up. Needs api 5.
     *
     * @since api 5
     */
    Subscription speller(String title, String initial, int maxLen, Listener<UiAction> onDone);
}
