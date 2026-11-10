package mibsdk.runtime;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Runs one mibpkg action for the SDK's own pages (a check for updates, an install). Blocking: the
 * runtime calls it on its package thread, never on the control thread.
 *
 * <p>The runtime does not link against mibpkg: it runs the installed binary, the same one the user's
 * other paths use, so an install from the MMI is exactly an install from the web UI.
 */
interface PackageOps {

    /** Runs {@code mibpkg <args>} and returns its last lines; never null, never throws. */
    String run(String... args);

    /** The real thing: {@code /mnt/app/root/mibpkg/bin/mibpkg}, or the {@code /tmp} copy, as found. */
    final class Mibpkg implements PackageOps {
        private static final String[] CANDIDATES = {"/mnt/app/root/mibpkg/bin/mibpkg", "/tmp/mibpkg"};
        private static final int MAX_CHARS = 2000;

        public String run(String... args) {
            String bin = binary();
            if (bin == null) {
                return "FAILED: mibpkg is not installed";
            }
            String[] cmd = new String[args.length + 1];
            cmd[0] = bin;
            System.arraycopy(args, 0, cmd, 1, args.length);
            Process p = null;
            try {
                // Runtime.exec, not ProcessBuilder: the car's class library has no ProcessBuilder.
                p = Runtime.getRuntime().exec(cmd);
                final Process proc = p;
                // A hung mibpkg must not wedge the SDK: the package thread gives up and says so.
                Thread killer = new Thread(new Runnable() {
                    public void run() {
                        try {
                            Thread.sleep(180000L);
                            proc.destroy();
                        } catch (InterruptedException ignored) {
                            // the process ended
                        }
                    }
                });
                killer.setDaemon(true);
                killer.start();
                String text = read(p.getInputStream());
                int code = p.waitFor();
                if (code != 0) {
                    String err = read(p.getErrorStream());
                    if (err.length() > 0) {
                        text = text.length() == 0 ? err : text + " " + err;
                    }
                }
                killer.interrupt();
                return code == 0 ? text : "FAILED (" + code + "): " + text;
            } catch (Throwable t) {
                return "FAILED: " + t;
            } finally {
                if (p != null) {
                    try {
                        p.getInputStream().close();
                    } catch (Throwable ignored) {
                        // read-only
                    }
                }
            }
        }

        private static String binary() {
            for (int i = 0; i < CANDIDATES.length; i++) {
                File f = new File(CANDIDATES[i]);
                if (f.isFile()) {
                    return CANDIDATES[i];
                }
            }
            return null;
        }

        /** The stream's last {@link #MAX_CHARS} characters, minus the trailing newline. */
        private static String read(java.io.InputStream in) throws Exception {
            StringBuilder b = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (b.length() > 0) {
                    b.append('\n');
                }
                b.append(line);
                if (b.length() > 4 * MAX_CHARS) {
                    b.delete(0, b.length() - MAX_CHARS);
                }
            }
            String s = b.toString().trim();
            return s.length() <= MAX_CHARS ? s : s.substring(s.length() - MAX_CHARS);
        }
    }
}
