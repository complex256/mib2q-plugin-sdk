package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Set;
import mibsdk.api.Net;
import mibsdk.api.PermissionDeniedException;
import mibsdk.api.Permissions;
import org.junit.jupiter.api.Test;

class NetImplTest {

    private static final Set<String> NET = Collections.singleton(Permissions.NETWORK);

    /** Accepts one connection on an ephemeral port; {@code body} serves it. */
    private interface Body {
        void serve(Socket c) throws Exception;
    }

    private static Thread serve(final ServerSocket ss, final Body body) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Socket c = ss.accept();
                    try {
                        body.serve(c);
                    } finally {
                        c.close();
                    }
                } catch (Exception ignored) {
                    // client gave up or the server was closed
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    void readsLinesUntilEof() throws Exception {
        final ServerSocket ss = new ServerSocket(0);
        try {
            final String[] seen = new String[1];
            serve(ss, new Body() {
                public void serve(Socket c) throws Exception {
                    InputStream in = c.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    int b;
                    while ((b = in.read()) != '\n') {
                        sb.append((char) b);
                    }
                    seen[0] = sb.toString();
                    c.getOutputStream().write("a=1\nb=2\n".getBytes("UTF-8"));
                }
            });
            String r = new NetImpl("p", NET).query("127.0.0.1", ss.getLocalPort(), "status", 2000);
            assertEquals("a=1\nb=2\n", r);
            assertEquals("status", seen[0]);
        } finally {
            ss.close();
        }
    }

    @Test
    void localhostIsAccepted() throws Exception {
        final ServerSocket ss = new ServerSocket(0);
        try {
            serve(ss, new Body() {
                public void serve(Socket c) throws Exception {
                    c.getOutputStream().write("ok".getBytes("UTF-8"));
                }
            });
            assertEquals("ok", new NetImpl("p", NET).query("localhost", ss.getLocalPort(), null, 2000));
        } finally {
            ss.close();
        }
    }

    @Test
    void timesOutWithinCap() throws Exception {
        final ServerSocket ss = new ServerSocket(0);
        final Object release = new Object();
        try {
            serve(ss, new Body() {
                public void serve(Socket c) throws Exception {
                    synchronized (release) {
                        release.wait(15000); // silent until the test ends
                    }
                }
            });
            long t0 = System.currentTimeMillis();
            assertThrows(SocketTimeoutException.class,
                    () -> new NetImpl("p", NET).query("127.0.0.1", ss.getLocalPort(), "x", 10000));
            long took = System.currentTimeMillis() - t0;
            assertTrue(took < 3500, "took " + took);
        } finally {
            synchronized (release) {
                release.notifyAll();
            }
            ss.close();
        }
    }

    @Test
    void capsResponseAt8KiB() throws Exception {
        final ServerSocket ss = new ServerSocket(0);
        try {
            serve(ss, new Body() {
                public void serve(Socket c) throws Exception {
                    OutputStream out = c.getOutputStream();
                    byte[] chunk = new byte[1000];
                    java.util.Arrays.fill(chunk, (byte) 'x');
                    for (int i = 0; i < 20; i++) {
                        out.write(chunk);
                    }
                }
            });
            String r = new NetImpl("p", NET).query("127.0.0.1", ss.getLocalPort(), null, 2000);
            assertEquals(Net.MAX_RESPONSE_BYTES, r.length());
        } finally {
            ss.close();
        }
    }

    @Test
    void deniedWithoutNetworkPermission() throws Exception {
        PermissionDeniedException e = assertThrows(PermissionDeniedException.class,
                () -> new NetImpl("p", Collections.<String>emptySet()).query("127.0.0.1", 1, null, 100));
        assertEquals(Permissions.NETWORK, e.permission());
    }

    @Test
    void refusesNonLoopbackHost() {
        String[] hosts = {"10.0.0.1", "0.0.0.0", "127.0.0.2", "example.com", null};
        for (String h : hosts) {
            IOException e = assertThrows(IOException.class, () -> new NetImpl("p", NET).query(h, 80, null, 100));
            assertTrue(e.getMessage().contains("loopback only"), e.getMessage());
        }
    }
}
