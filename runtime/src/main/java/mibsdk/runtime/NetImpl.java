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

    /** Largest request, in UTF-8 bytes before the newline; the write has no deadline, so it must fit the socket buffer. */
    static final int MAX_REQUEST_BYTES = 2048;

    private final Set<String> granted;
    private final Clock clock;

    /** @param clock the SDK's monotonic clock: the unit's wall clock starts at 1970 and is stepped by GPS */
    NetImpl(Set<String> granted, Clock clock) {
        this.granted = granted;
        this.clock = clock;
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
        byte[] req = null;
        if (request != null) {
            req = (request + "\n").getBytes("UTF-8");
            if (req.length - 1 > MAX_REQUEST_BYTES) {
                throw new IOException("network: request too large");
            }
        }
        int timeout = Math.max(1, Math.min(timeoutMs, MAX_TIMEOUT_MS));
        long deadline = clock.uptimeMs() + timeout;
        Socket s = new Socket();
        try {
            // Both names go to the literal address, so no resolver is involved.
            s.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), timeout);
            if (req != null) {
                OutputStream out = s.getOutputStream();
                out.write(req);
                out.flush();
            }
            InputStream in = s.getInputStream();
            byte[] buf = new byte[MAX_RESPONSE_BYTES];
            int n = 0;
            byte[] sink = new byte[512];
            while (true) {
                long left = deadline - clock.uptimeMs();
                if (left <= 0) {
                    throw new SocketTimeoutException("network: read timed out");
                }
                // Also bounded by the per-call timeout, so a clock that is slow to tick cannot stretch a read.
                s.setSoTimeout((int) Math.min(left, timeout));
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
