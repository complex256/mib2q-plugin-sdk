package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.IHMIServiceApp;
import de.audi.atip.hmi.modelaccess.ChoiceModelApp;
import de.audi.atip.hmi.modelaccess.LabelModelApp;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import mibsdk.platform.UiStatusPart;
import org.junit.jupiter.api.Test;

class Popup65StatusLineTest {

    /** Popup 65 draws only the text line; the parts are for the SDK popup. */
    private static final List<UiStatusPart> NO_PARTS =
            Collections.singletonList(new UiStatusPart("ignored", null, false));

    private final List<String> calls = new ArrayList<String>();

    @SuppressWarnings("unchecked")
    private <T> T fake(Class<T> iface, String name) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {iface}, (p, m, a) -> {
            switch (m.getName()) {
                case "getHmiServiceApp":
                    return fake(IHMIServiceApp.class, "app");
                case "getLabelModel":
                    return fake(LabelModelApp.class, "label" + a[0]);
                case "getChoiceModel":
                    return fake(ChoiceModelApp.class, "choice" + a[0]);
                case "setText":
                case "setValue":
                    calls.add(name + "." + m.getName() + "(" + a[0] + ")");
                    return null;
                default:
                    return null;
            }
        });
    }

    @Test
    void showWritesTextThenVisibleHideWritesZero() {
        Popup65StatusLine line = new Popup65StatusLine(fake(IFrameworkAccess.class, "fw"), 24);
        line.show(NO_PARTS, "Hello");
        line.show(NO_PARTS, "0123456789012345678901234567890"); // the popup fits 24
        line.hide();
        assertEquals(Arrays.asList("label416.setText(Hello)", "choice1.setValue(1)",
                "label416.setText(012345678901234567890...)", "choice1.setValue(1)", "choice1.setValue(0)"), calls);
    }

    @Test
    void honoursStatusMaxCharsWithinFourToTwentyFour() {
        String text = "0123456789012345678901234567890";
        new Popup65StatusLine(fake(IFrameworkAccess.class, "fw"), 10).show(NO_PARTS, text); // ui.status.max.chars=10
        new Popup65StatusLine(fake(IFrameworkAccess.class, "fw"), 1).show(NO_PARTS, text); // below the minimum: 4
        new Popup65StatusLine(fake(IFrameworkAccess.class, "fw"), 40).show(NO_PARTS, text); // the popup fits only 24
        assertEquals(Arrays.asList("label416.setText(0123456...)", "choice1.setValue(1)",
                "label416.setText(0...)", "choice1.setValue(1)",
                "label416.setText(012345678901234567890...)", "choice1.setValue(1)"), calls);
    }
}
