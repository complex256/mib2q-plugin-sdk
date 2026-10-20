package mibsdk.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import mibsdk.api.Net;
import mibsdk.api.PermissionDeniedException;
import mibsdk.api.Permissions;

/**
 * Loopback-only TCP query for plugins. Runs on the calling (plugin) thread, never creates one. Hosts other
 * than {@code 127.0.0.1} and {@code localhost} are refused before any socket exists; the connect and the
 * whole read share one deadline of {@code min(timeoutMs, MAX_TIMEOUT_MS)}; the response is capped at
 * {@link Net#MAX_RESPONSE_BYTES} (the excess is dropped).
 */
final class NetImpl implements Net {

    private final String pluginId;
    private final Set<String> granted;

    NetImpl(String pluginId, Set<String> granted) {
        this.pluginId = pluginId;
        this.granted = granted;
    }

    String pluginId() {
        return pluginId;
    }

    @Override
    public String query(String host, int port, String request, int timeoutMs) throws IOException {
        if (!granted.contains(Permissions.NETWORK)) {
            throw new PermissionDeniedException(Permissions.NETWORK);
        }
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            throw new IOException("network: loopback only");
        }
        if (port < 1 || port > 65535) {
            throw new IOException("network: bad port");
        }
        int timeout = Math.max(1, Math.min(timeoutMs, MAX_TIMEOUT_MS));
        long deadline = System.currentTimeMillis() + timeout;
        Socket s = new Socket();
        try {
            // Both names go to the literal address, so no resolver is involved.
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeout);
            if (request != null) {
                OutputStream out = s.getOutputStream();
                out.write((request + "\n").getBytes("UTF-8"));
                out.flush();
            }
            InputStream in = s.getInputStream();
            byte[] buf = new byte[MAX_RESPONSE_BYTES];
            int n = 0;
            byte[] sink = new byte[512];
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    throw new SocketTimeoutException("network: read timed out");
                }
                s.setSoTimeout((int) left);
                int r;
                if (n < buf.length) {
                    r = in.read(buf, n, buf.length - n);
                    if (r > 0) {
                        n += r;
                    }
                } else {
                    r = in.read(sink, 0, sink.length); // full: drain and drop the excess
                }
                if (r < 0) {
                    break;
                }
            }
            return new String(buf, 0, n, "UTF-8");
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing a failed socket
            }
        }
    }
}
