package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.IHMIServiceApp;
import de.audi.atip.hmi.modelaccess.ChoiceModelApp;
import de.audi.atip.hmi.modelaccess.LabelModelApp;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class Popup65StatusLineTest {

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
        Popup65StatusLine line = new Popup65StatusLine(fake(IFrameworkAccess.class, "fw"));
        line.show("Hello");
        line.show("0123456789012345678901234567890"); // the popup fits 24
        line.hide();
        assertEquals(Arrays.asList("label416.setText(Hello)", "choice1.setValue(1)",
                "label416.setText(012345678901234567890...)", "choice1.setValue(1)", "choice1.setValue(0)"), calls);
    }
}
