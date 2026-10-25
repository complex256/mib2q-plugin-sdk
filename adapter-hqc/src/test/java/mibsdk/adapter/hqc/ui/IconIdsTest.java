package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import mibsdk.adapter.hqc.HqcPlatform;
import mibsdk.adapter.hqc.SdkGlobals;
import mibsdk.platform.PlatformHost;
import mibsdk.runtime.RingLog;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;

/** Review Focus 2: one id per owner/content key per session, never reused or re-pointed. */
class IconIdsTest {

    @Test
    void sameNameNewContentGetsANewIdAndTheOldIdKeepsItsFile() {
        IconIds ids = new IconIds(609100, 609101);
        assertEquals(609100, ids.idFor("hello", "aaaa000000000000", "/i/hello/aaaa000000000000.png", "../i/a.png"));
        assertEquals(609100, ids.idFor("hello", "aaaa000000000000", "/i/hello/aaaa000000000000.png", "../i/a.png"));
        assertEquals(609101, ids.idFor("hello", "bbbb000000000000", "/i/hello/bbbb000000000000.png", "../i/b.png"));
        assertEquals("/i/hello/aaaa000000000000.png", ids.fileFor(609100));
        assertEquals(-1, ids.idFor("hello", "cccc000000000000", "/i/hello/cccc000000000000.png", "../i/c.png"));
        assertTrue(ids.exhausted());
        assertEquals("../i/a.png", ids.relPathFor(609100), "never re-pointed");
        assertEquals("../i/b.png", ids.relPathFor(609101));
        assertNull(ids.relPathFor(609102));
        assertNull(ids.relPathFor(609099));
    }

    @Test
    void identicalBytesInTwoPackagesGetTwoIds() {
        IconIds ids = new IconIds(609100, 609399);
        int a = ids.idFor("a", "0123456789abcdef", "/i/a/0123456789abcdef.png", "../i/a/0123456789abcdef.png");
        int b = ids.idFor("b", "0123456789abcdef", "/i/b/0123456789abcdef.png", "../i/b/0123456789abcdef.png");
        assertNotEquals(a, b);
        assertEquals("/i/a/0123456789abcdef.png", ids.fileFor(a));
        assertEquals("/i/b/0123456789abcdef.png", ids.fileFor(b));
        assertFalse(ids.exhausted());
    }

    @SuppressWarnings("unchecked")
    private static <T> T nulls(Class<T> iface) {
        return (T) Proxy.newProxyInstance(IconIdsTest.class.getClassLoader(), new Class<?>[] {iface}, (p, m, a) -> {
            switch (m.getName()) {
                case "hashCode":
                    return System.identityHashCode(p);
                case "equals":
                    return p == a[0];
                case "toString":
                    return iface.getSimpleName();
                default:
                    return null;
            }
        });
    }

    @Test
    void platformAndApplierShareTheSessionInstance() {
        IconIds session = SdkGlobals.ICON_IDS;
        int id = session.idFor("share-test", "dddd000000000000", "/i/share-test/dddd000000000000.png",
                "../i/share-test/dddd000000000000.png");
        assertTrue(id >= HqcUiIds.ICON_ID_FIRST && id <= HqcUiIds.ICON_ID_LAST, "id " + id);

        boolean ui = SdkGlobals.uiEnabled;
        try {
            SdkGlobals.uiEnabled = true; // no HMI framework service: the platform waits, no applier thread
            HqcPlatform p = new HqcPlatform(nulls(BundleContext.class));
            p.start(nulls(PlatformHost.class));
            p.stop();
        } finally {
            SdkGlobals.uiEnabled = ui;
        }
        RingLog log = new RingLog(10);
        UiApplier first = UiApplier.create(nulls(de.audi.atip.base.IFrameworkAccess.class), () -> 0L, log, 24);
        UiApplier second = UiApplier.create(nulls(de.audi.atip.base.IFrameworkAccess.class), () -> 0L, log, 24);
        assertSame(session, SdkGlobals.ICON_IDS);
        assertSame(session, first.iconIds());
        assertSame(session, second.iconIds());
        assertEquals("/i/share-test/dddd000000000000.png", SdkGlobals.ICON_IDS.fileFor(id));
        assertEquals(id, second.iconIds().idFor("share-test", "dddd000000000000", "x", "y"), "same key, same id");
    }
}
