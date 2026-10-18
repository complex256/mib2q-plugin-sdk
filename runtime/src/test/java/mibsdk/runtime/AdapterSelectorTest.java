package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import mibsdk.adapter.fake.FakePlatform;
import mibsdk.platform.ParkingIo;
import mibsdk.platform.PlatformAdapter;
import mibsdk.platform.PlatformHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterSelectorTest {

    private static final String SUPPORTED = "HMI_AU_MIB2_HQC_NAR_G22_C10_180803";

    @TempDir
    File tmp;

    private File fixture() throws IOException {
        InputStream in = AdapterSelectorTest.class.getResourceAsStream("/version_info.txt");
        File f = new File(tmp, "version_info.txt");
        FileOutputStream out = new FileOutputStream(f);
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
            out.close();
        }
        return f;
    }

    private File file(String text) throws IOException {
        File f = new File(tmp, "v.txt");
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(text.getBytes("ISO-8859-1"));
        } finally {
            out.close();
        }
        return f;
    }

    @Test
    void readsHmiLine() throws Exception {
        assertEquals(SUPPORTED, AdapterSelector.readHmiVersion(fixture()));
    }

    @Test
    void readsHmiLineWithCrLfTabsAndLookalikeKeys() throws Exception {
        assertEquals(SUPPORTED, AdapterSelector.readHmiVersion(file(
                "HMI_Version = nope\r\nHMIX = nope\r\n\tHMI\t=  " + SUPPORTED + " \r\nJavaVM = j9\r\n")));
    }

    @Test
    void missingHmiLineOrFileReturnsNull() throws Exception {
        assertNull(AdapterSelector.readHmiVersion(file("Product = mmx2qc\nBranch = dev/main\n")));
        assertNull(AdapterSelector.readHmiVersion(file("HMI =   \n")));
        assertNull(AdapterSelector.readHmiVersion(new File(tmp, "absent.txt")));
        assertNull(AdapterSelector.readHmiVersion(null));
    }

    @Test
    void selectsTheAdapterSupportingTheVersion() {
        FakePlatform other = new FakePlatform("HMI_OTHER", new HashSet<String>());
        FakePlatform match = new FakePlatform(SUPPORTED, new HashSet<String>());
        assertSame(match, AdapterSelector.select(SUPPORTED, new PlatformAdapter[] {other, match}));
    }

    @Test
    void noMatchReturnsNull() {
        FakePlatform a = new FakePlatform(SUPPORTED, new HashSet<String>());
        assertNull(AdapterSelector.select("HMI_AU_MIB2_HQC_NAR_G22_C10_190101", new PlatformAdapter[] {a}));
        assertNull(AdapterSelector.select(SUPPORTED.toLowerCase(), new PlatformAdapter[] {a}), "exact compare");
        assertNull(AdapterSelector.select(null, new PlatformAdapter[] {a}));
        assertNull(AdapterSelector.select(SUPPORTED, new PlatformAdapter[0]));
        assertNull(AdapterSelector.select(SUPPORTED, null));
    }

    @Test
    void throwingCandidateIsSkipped() {
        PlatformAdapter broken = new PlatformAdapter() {
            public String id() {
                return "broken";
            }

            public boolean supports(String hmiVersion) {
                throw new IllegalStateException("bug");
            }

            public Set<String> features() {
                return new HashSet<String>(Arrays.asList(new String[0]));
            }

            public void start(PlatformHost host) {
            }

            public void stop() {
            }

            public ParkingIo parking() {
                return null;
            }
        };
        FakePlatform match = new FakePlatform(SUPPORTED, new HashSet<String>());
        assertSame(match, AdapterSelector.select(SUPPORTED, new PlatformAdapter[] {broken, null, match}));
    }
}
