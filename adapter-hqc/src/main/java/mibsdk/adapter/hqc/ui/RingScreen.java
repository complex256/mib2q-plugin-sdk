package mibsdk.adapter.hqc.ui;

import de.audi.atip.hmi.event.KeyEvent;
import de.audi.atip.hmi.view.HMIView;
import de.audi.atip.hmi.view.Screen;
import de.audi.tghu.system.hmi.evohigh.FontFactory;
import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.evo.IWidgetKeyHandler;
import de.esolutions.hmi.widgets.audi.evo.ScreenWidgetEVO;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.MainWizardRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.ScreenRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.MainWizardController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import java.lang.reflect.Field;
import java.util.List;
import mibsdk.adapter.hqc.SdkGlobals;
import mibsdk.api.StockIcons;
import mibsdk.platform.UiEntry;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiPage;

/**
 * A ring page as the stock 3D wizard: the recipe of screen 46 ({@code SystemScreenBag2.mAINWIZARD}), with the page's
 * LINK/ITEM rows as the ring's items. Shown by {@link PageScreens} instead of a page screen when
 * {@link UiPage#ring()} is set, and only while the unit can build it: any failure leaves the page to the list
 * screen.
 *
 * <p>Icons: a ring item's glyph is a cell of the HMI's own 3D atlas ({@code iconAtlas.png}, 21 cells). A
 * {@link UiIcon} whose name is one of the {@link StockIcons} ring names maps to its cell; any other icon (a plugin's
 * own PNG, or a shape name) shows the fallback cell, because this renderer draws atlas cells only. The mapping is the
 * layout's, which lacks the unused wrench cell (16): the ring sets its own 21-entry mapping on this controller, so
 * every cell we use resolves. That field is per controller, so the stock ring keeps its own.
 *
 * <p>Built on HMIEvent (the page renderer's thread): stock widgets only, no I/O, no thread, never throws out.
 */
final class RingScreen {

    private static final String SOURCE = "mibsdk";

    /** The atlas cell per {@link StockIcons} ring name; {@link #FALLBACK} for anything else. */
    private static final int FALLBACK = 14; // the Audi flower: "something in here"

    /** Screen 46's five colour palettes, so the ring follows the car's day/night theme. */
    private static final int[][] PALETTES = {
        {-1, 255, -1, -1, -1701143809, -1431655681, -1246382593, -1701143809, -1, -1, -1, -1, -1, -1, -1, -1,
                419495935, -19456, -1433657601, 11166975},
        {-1, 255, 419495935, 1751100415, -1701143809, -1431655681, -1246382593, -1701143809, -1, -1, -1, -1, -1, -1,
                -1, -1, 419495935, -19456, -1433657601, 11166975},
        {-1, 255, -19456, 1838260991, -1701143809, -1431655681, -1246382593, -1701143809, -1, -1, -1, -1, -1, -1, -1,
                -1, 419495935, -19456, -1433657601, 11166975},
        {-1, 255, -1433657601, -1567134209, -1701143809, -1431655681, -1246382593, -1701143809, -1, -1, -1, -1, -1,
                -1, -1, -1, 419495935, -19456, -1433657601, 11166975},
        {-1, 255, 11166975, 1973122047, -1701143809, -1431655681, -1246382593, -1701143809, -1, -1, -1, -1, -1, -1,
                -1, -1, 419495935, -19456, -1433657601, 11166975}};

    /** The layout's mapping with the wrench cell (16) filled in; index = atlas cell, value = {icon, decorator}. */
    private static final int[][] MAPPING = {
        {0, 0}, {1, 1}, {2, 2}, {3, 3}, {4, 4}, {5, 5}, {6, 6}, {7, 7}, {8, 8}, {9, 9}, {10, 10}, {11, 11},
        {12, 3}, {13, 3}, {14, 5}, {15, 5}, {16, 9}, {17, 12}, {18, 12}, {19, 12}, {20, 12}};

    private RingScreen() {
    }

    /**
     * The ring screen for {@code page}: its LINK and ITEM rows become the wizard's items, in page order. {@code focus}
     * is the item to focus (an index into those rows), or -1 for the first. Throws if the recipe fails; the caller
     * falls back to the page screen.
     */
    static Screen build(SdkCarScreenFactory f, int terminal, PageScreens owner, String pageKey, UiPage page,
                        int focus) throws Exception {
        List<UiEntry> all = page.entries();
        int n = 0;
        for (int i = 0; i < all.size(); i++) {
            int k = all.get(i).kind();
            if (k == UiEntry.LINK || k == UiEntry.ITEM) {
                n++;
            }
        }
        if (n == 0) {
            throw new IllegalStateException("ring page " + pageKey + " has no LINK/ITEM rows");
        }
        String[] labels = new String[n];
        int[] cells = new int[n];
        int[] rowIndex = new int[n];
        int at = 0;
        for (int i = 0; i < all.size(); i++) {
            UiEntry e = all.get(i);
            if (e.kind() != UiEntry.LINK && e.kind() != UiEntry.ITEM) {
                continue;
            }
            labels[at] = e.label() == null ? "" : e.label();
            cells[at] = cell(e.icon());
            rowIndex[at] = i;
            at++;
        }
        SdkGlobals.ringLabels = labels; // the factory's getText serves them (HMIEvent: the same thread)

        MainWizardController wizard = new MainWizardController();
        MainWizardRendererHigh renderer = new MainWizardRendererHigh(wizard);
        wizard.setRenderer(renderer);
        renderer.setFonts(FontFactory.getFonts(4, terminal, f.framework()));
        wizard.setModelID(HqcUiIds.RING_MODEL);
        wizard.setBounds(0, 0, 540, 100);
        setMapping(wizard);

        for (int i = 0; i < n; i++) {
            MenuItemController row = new MenuItemController();
            row.setType(HqcUiIds.DRAWER_ROW_TYPE_ACTION);
            row.setLabelId(HqcUiIds.TEXT_RING_BASE + i);
            // The widget id is the item's atlas cell: it picks the glyph, and the wizard writes it into the ring's
            // model on every turn, which is what brings the focus back to this item when the ring is rebuilt.
            row.setWidgetID(cells[i]);
            row.setColorIndices(new int[] {0, 0, 4, 0});
            row.setGlassplateInsetsTop(7);
            row.setGlassplateInsetsBottom(6);
            row.setInternalID(HqcUiIds.ROW_INTERNAL_ID_BASE + i);
            row.setKeyHandler(new RingKey(owner, rowIndex[i]));
            row.setVisible(true);
            wizard.add(row);
        }

        ScreenWidgetEVO s = new RingScreenWidget(HqcUiIds.RING_SCREEN_ID, owner);
        ScreenRendererHigh sr = new ScreenRendererHigh(s);
        s.setRenderer(sr);
        sr.setFonts(FontFactory.getFonts(3, terminal, f.framework()));
        s.setScreenFactory(f);
        s.setColorIndices(new int[] {1, 2, 4, 0});
        s.setColorPalettes(PALETTES);
        s.setSmallStageType(3);
        s.add(wizard, 1);
        s.setViews(new int[] {HqcUiIds.RING_MODEL}, new HMIView[][] {{wizard}});
        s.setModelIDs(new int[] {HqcUiIds.RING_MODEL});
        return s;
    }

    /** The atlas cell for {@code icon}: its {@link StockIcons} name, else {@link #FALLBACK}. */
    static int cell(UiIcon icon) {
        String name = icon == null ? null : icon.name();
        if (name == null) {
            return FALLBACK;
        }
        if (StockIcons.CAR.equals(name)) {
            return 0;
        }
        if (StockIcons.RADIO.equals(name)) {
            return 1;
        }
        if (StockIcons.MEDIA.equals(name)) {
            return 2;
        }
        if (StockIcons.PHONE.equals(name)) {
            return 3;
        }
        if (StockIcons.NAVIGATION.equals(name)) {
            return 4;
        }
        if (StockIcons.MAP.equals(name)) {
            return 5;
        }
        if (StockIcons.GLOBE.equals(name)) {
            return 6;
        }
        if (StockIcons.OFFICE.equals(name)) {
            return 7;
        }
        if (StockIcons.SOUND.equals(name)) {
            return 8;
        }
        if (StockIcons.SETTINGS.equals(name)) {
            return 9;
        }
        if (StockIcons.WARNING.equals(name)) {
            return 10;
        }
        if (StockIcons.NIGHT_VISION.equals(name)) {
            return 11;
        }
        if (StockIcons.HEADSET.equals(name)) {
            return 12;
        }
        if (StockIcons.SERVICE.equals(name)) {
            return 13;
        }
        if (StockIcons.FLOWER.equals(name)) {
            return 14;
        }
        if (StockIcons.TRAFFIC.equals(name)) {
            return 15;
        }
        if (StockIcons.WRENCH.equals(name)) {
            return 16;
        }
        if (StockIcons.SMARTPHONE.equals(name)) {
            return 19;
        }
        return FALLBACK;
    }

    /**
     * Gives this wizard {@link #MAPPING} (the layout's, with the wrench cell). The field is private and read once, on
     * the wizard's first connect; without it the wrench cell resolves to the CarPlay cell. Never throws: a refusal
     * leaves the layout's own mapping, which every other cell still resolves in.
     */
    private static void setMapping(MainWizardController wizard) {
        try {
            Field f = MainWizardController.class.getDeclaredField("mainWizardIconDecoratorMapping");
            f.setAccessible(true);
            f.set(wizard, MAPPING);
        } catch (Throwable t) {
            SdkGlobals.LOG.append("WARN", SOURCE, "ui ring: icon mapping left to the layout: " + t);
        }
    }

    /**
     * The ring screen itself: BACK leaves the ring (to the screen it was opened from), and MENU does what it does on
     * every other SDK page. A plain {@code ScreenWidgetEVO} has no menu to consume BACK, and MENU is an app-change
     * hard key that only {@link #doesHKFilterContainsKey} sees.
     */
    private static final class RingScreenWidget extends ScreenWidgetEVO {
        private final PageScreens owner;

        RingScreenWidget(int id, PageScreens owner) {
            super(id);
            this.owner = owner;
        }

        public boolean doesHKFilterContainsKey(int keyCode) {
            if (keyCode == HqcUiIds.KEY_MENU && owner.menuPressed()) {
                return true;
            }
            return super.doesHKFilterContainsKey(keyCode);
        }

        public void keyPressed(KeyEvent e) {
            try {
                if (e != null && e.getKeyCode() == HqcUiIds.KEY_BACK) {
                    e.consume(false);
                    owner.backPressed();
                    return;
                }
            } catch (Throwable t) {
                // stock handling below
            }
            super.keyPressed(e);
        }
    }

    /** One ring item: SELECT opens it (a LINK) or fires it (an ITEM); the page renderer does the rest. */
    private static final class RingKey implements IWidgetKeyHandler {
        private final PageScreens owner;
        private final int entry;

        RingKey(PageScreens owner, int entry) {
            this.owner = owner;
            this.entry = entry;
        }

        public void keyPressed(AbstractWidgetController w, KeyEvent e) {
            try {
                if (e == null || e.getKeyCode() != HqcUiIds.KEY_ENTER) {
                    return;
                }
                e.consume(false);
                owner.ringSelected(entry);
            } catch (Throwable t) {
                // never propagate into the HMI
            }
        }

        public void keyReleased(AbstractWidgetController w, KeyEvent e) {
        }

        public void keyTurned(AbstractWidgetController w, de.audi.atip.hmi.event.WheelButtonEvent e) {
        }

        public void keyMoved(AbstractWidgetController w, de.audi.atip.hmi.event.JoystickEvent e) {
        }
    }
}
