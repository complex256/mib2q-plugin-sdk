package mibsdk.runtime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import mibsdk.platform.PlatformAdapter;

/**
 * Picks the platform adapter for this firmware. The HMI version is the value of the {@code HMI = ...} line of
 * {@code /ifs/version_info.txt}; adapters compare it exactly. No match means the SDK stays off.
 */
public final class AdapterSelector {

    private static final String KEY = "HMI";
    private static final int MAX_LINES = 1000;

    private AdapterSelector() {
    }

    /** The value of the {@code HMI = ...} line, trimmed; null if absent, empty or unreadable. Never throws. */
    public static String readHmiVersion(File versionInfo) {
        if (versionInfo == null || !versionInfo.isFile()) {
            return null;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(versionInfo), "ISO-8859-1"));
            String line;
            for (int n = 0; n < MAX_LINES && (line = r.readLine()) != null; n++) {
                int eq = line.indexOf('=');
                if (eq > 0 && KEY.equals(line.substring(0, eq).trim())) {
                    String v = line.substring(eq + 1).trim();
                    return v.length() == 0 ? null : v;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                    // read-only stream
                }
            }
        }
    }

    /** The first candidate that supports {@code hmiVersion}; null if none does (or the version is unknown). */
    public static PlatformAdapter select(String hmiVersion, PlatformAdapter[] candidates) {
        if (hmiVersion == null || candidates == null) {
            return null;
        }
        for (int i = 0; i < candidates.length; i++) {
            PlatformAdapter a = candidates[i];
            try {
                if (a != null && a.supports(hmiVersion)) {
                    return a;
                }
            } catch (Throwable t) {
                // a broken adapter is simply not a match
            }
        }
        return null;
    }
}
