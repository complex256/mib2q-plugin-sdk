package mibsdk.adapter.hqc.ui;

import mibsdk.platform.UiDialog;
import mibsdk.platform.UiSnapshot;

/**
 * Draws SDK pages. {@link #open}, {@link #refresh}, {@link #close}, {@link #runPendingNav} and {@link #dialogChanged}
 * run inside an {@link HmiPoster} job, on HMIEvent; {@link #closePending}, {@link #navPending}, {@link #itemIcons} and
 * {@link #shown} are called on {@code mibsdk-ui}.
 */
interface PageRenderer {

    /**
     * The runtime asked to open {@code pageKey} (a new {@code openSeq}), {@code requestAgeMs} ago on the monotonic
     * clock (since the applier first saw the request).
     */
    void open(String pageKey, UiSnapshot s, long requestAgeMs);

    /** Page content changed: redraw what is on screen, if anything. */
    void refresh(UiSnapshot s);

    /**
     * Leaves SDK pages: back to the stock screen the bottom page came from (runtime down). Never throws; with no SDK
     * page on screen it changes no screen.
     */
    void close();

    /**
     * A close could not change the screen yet (RVC/PDC): while true, the down applier posts the close again on each
     * tick. Read on {@code mibsdk-ui}.
     */
    boolean closePending();

    /**
     * A page asked for navigation (LINK, CHOICE, a choice pick, BACK) that has not run yet: the applier posts its job
     * for it, also when no page changed (F2). Read on {@code mibsdk-ui}.
     */
    boolean navPending();

    /**
     * A rebuild of the shown page (new title or icon layout) was deferred because the screen could not change
     * (popup, parking display): while true, the applier renders again once per tick, as for {@link #closePending}.
     * Read on {@code mibsdk-ui}.
     */
    boolean rebuildPending();

    /** In the applier's job: runs the navigation asked for since the last job, if any. Never throws. */
    void runPendingNav();

    /** On {@code mibsdk-ui}: the item icons' image ids for the pages drawn from now on (kept in a volatile field). */
    void itemIcons(ItemIcons ids);

    /**
     * The page screen shown last (its key, layout and generation), or null: the applier writes the typed rows' pooled
     * models for it. Read on {@code mibsdk-ui} (a volatile field).
     */
    PageScreens.Shown shown();

    /**
     * In the applier's job, after the pages are rendered: the snapshot's dialog ({@code null} for none). A new one is
     * shown over its owner's page or answered as not shown; one withdrawn while its screen is up goes back to the
     * page. Called with every render; the same dialog again changes nothing. Never throws.
     */
    void dialogChanged(UiDialog d);

    /** No SDK pages. */
    PageRenderer NONE = new PageRenderer() {
        public void open(String pageKey, UiSnapshot s, long requestAgeMs) {
        }

        public void refresh(UiSnapshot s) {
        }

        public void close() {
        }

        public boolean closePending() {
            return false;
        }

        public boolean navPending() {
            return false;
        }

        public boolean rebuildPending() {
            return false;
        }

        public void runPendingNav() {
        }

        public void itemIcons(ItemIcons ids) {
        }

        public PageScreens.Shown shown() {
            return null;
        }

        public void dialogChanged(UiDialog d) {
        }
    };
}
