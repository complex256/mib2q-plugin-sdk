package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import de.audi.atip.hmi.view.HMIView;
import de.esolutions.hmi.widgets.audi.evo.widgets.DrawerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.ContainerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Carried requirement 3: the SDK rows go in whole or not at all; the drawer is never left half-built. */
class SdkCarScreenFactoryTest {

    /** A drawer whose view mapping fails. */
    static final class FailingDrawer extends DrawerController {
        int setViewsCalls;

        FailingDrawer() {
            super(HqcUiIds.DRAWER_ID);
        }

        public void setViews(int[] ids, HMIView[][] views) {
            setViewsCalls++;
            throw new IllegalStateException("setViews failed");
        }
    }

    private static MenuItemController[] rows() {
        MenuItemController[] r = new MenuItemController[HqcUiIds.ROW_SLOTS];
        for (int i = 0; i < r.length; i++) {
            r[i] = new MenuItemController();
        }
        return r;
    }

    @SuppressWarnings("unchecked") // stock getChildren() is a raw List
    private static List<Object> children(ContainerController m) {
        return m.getChildren() == null ? new ArrayList<Object>() : new ArrayList<Object>(m.getChildren());
    }

    @Test
    void failedViewMappingRemovesTheRowsAgain() {
        FailingDrawer d = new FailingDrawer();
        ContainerController menu = new ContainerController();
        MenuItemController stock = new MenuItemController();
        menu.add(stock);
        List<Object> before = children(menu);

        SdkCarScreenFactory.install(d, menu, rows(), new int[] {1}, new HMIView[][] {new HMIView[0]});
        assertEquals(1, d.setViewsCalls, "children were added first, then the views mapped");
        assertEquals(before, children(menu), "rolled back: only the stock row remains");
        assertSame(stock, children(menu).get(0));
    }

    @Test
    void successAddsEveryRowThenMapsTheViews() {
        List<Integer> childCountAtSetViews = new ArrayList<Integer>();
        ContainerController menu = new ContainerController();
        DrawerController d = new DrawerController(HqcUiIds.DRAWER_ID) {
            public void setViews(int[] ids, HMIView[][] views) {
                childCountAtSetViews.add(menu.getChildren().size());
            }
        };
        SdkCarScreenFactory.install(d, menu, rows(), new int[] {1}, new HMIView[][] {new HMIView[0]});
        assertEquals(java.util.Collections.singletonList(HqcUiIds.ROW_SLOTS), childCountAtSetViews);
        assertEquals(HqcUiIds.ROW_SLOTS, children(menu).size());
    }
}
