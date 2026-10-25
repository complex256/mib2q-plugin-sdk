package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiStatusPart;
import org.junit.jupiter.api.Test;

class SdkPopupStatusLineTest {

    private final List<String> calls = new ArrayList<String>();

    private final SdkPopupStatusLine.Models models = new SdkPopupStatusLine.Models() {
        public void label(int i, String text) {
            calls.add("label" + i + "=" + text);
        }

        public void fileIcon(int i, String path) {
            calls.add("file" + i + "=" + path);
        }

        public void visible(boolean on) {
            calls.add("visible=" + on);
        }
    };

    private static List<UiStatusPart> line(String item, UiIcon icon) {
        List<UiStatusPart> p = new ArrayList<UiStatusPart>();
        p.add(new UiStatusPart("SAFE", null, true));
        p.add(new UiStatusPart(item, icon, false));
        return p;
    }

    private static UiIcon file(String sha16) {
        return UiIcon.file("hello", "/i/" + sha16 + ".png", sha16, 20, 20);
    }

    @Test
    void writesEveryModelFirstThenOnlyWhatChangedAndShows() {
        SdkPopupStatusLine s = new SdkPopupStatusLine(() -> models);
        s.show(line("TS up", file("aaaa000000000000")), "SAFE TS up");
        assertEquals(Arrays.asList("label0=SAFE", "label1=TS up", "label2=", "label3=", "label4=", "label5=", "label6=",
                "file0=/i/aaaa000000000000.png", "file1=null", "file2=null", "file3=null", "file4=null", "file5=null",
                "visible=true"), calls);
        calls.clear();
        s.show(line("TS dn", file("bbbb000000000000")), "SAFE TS dn");
        assertEquals(Arrays.asList("label1=TS dn", "file0=/i/bbbb000000000000.png", "visible=true"), calls);
        calls.clear();
        s.show(line("TS dn", UiIcon.stock("dot")), "SAFE TS dn");
        assertEquals(Arrays.asList("file0=null", "visible=true"), calls, "a stock icon is a bitmap, not a path");
        calls.clear();
        s.hide();
        assertEquals(Arrays.asList("visible=false"), calls);
    }

    @Test
    void noModelsYetFailsTheShow() {
        SdkPopupStatusLine s = new SdkPopupStatusLine(() -> null);
        assertThrows(IllegalStateException.class, () -> s.show(line("A", null), "SAFE A"));
    }
}
