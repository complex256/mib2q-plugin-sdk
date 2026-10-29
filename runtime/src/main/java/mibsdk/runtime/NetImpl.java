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
 * TCP query for plugins. Runs on the calling (plugin) thread, never creates one for the query itself.
 *
 * <p>Loopback hosts ({@code 127.0.0.1} and {@code localhost}) need the {@code network} permission.
 * Any other host also needs {@code network.outbound}; without it the call is refused before a socket
 * exists. DNS resolution is bounded by the same deadline as the connect and the read, and runs on a
 * short-lived daemon thread so a stuck resolver cannot hang the plugin worker.
 *
 * <p>The connect and the whole read share one deadline of {@code min(timeoutMs, MAX_TIMEOUT_MS)}; the
 * response is capped at {@link Net#MAX_RESPONSE_BYTES} (the excess is dropped).
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
        if (host == null || host.length() == 0) {
            throw new IOException("network: bad host");
        }
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host);
        if (!loopback && !granted.contains(Permissions.NETWORK_OUTBOUND)) {
            throw new PermissionDeniedException(Permissions.NETWORK_OUTBOUND);
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
        InetAddress address = loopback
                ? InetAddress.getByAddress(new byte[] {127, 0, 0, 1})
                : resolve(host, deadline);
        Socket s = new Socket();
        try {
            long left = deadline - clock.uptimeMs();
            if (left <= 0) {
                throw new SocketTimeoutException("network: connect timed out");
            }
            s.connect(new InetSocketAddress(address, port), (int) Math.min(left, timeout));
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
                left = deadline - clock.uptimeMs();
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

    /**
     * Resolves {@code host} on a daemon thread, bounded by {@code deadline}. A resolver that ignores the
     * interrupt is left behind as a daemon; it cannot outlive the HMI.
     */
    private InetAddress resolve(final String host, final long deadline) throws IOException {
        final Object lock = new Object();
        final InetAddress[] result = new InetAddress[1];
        final IOException[] failure = new IOException[1];
        final boolean[] done = new boolean[1];
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    result[0] = InetAddress.getByName(host);
                } catch (IOException e) {
                    failure[0] = e;
                } finally {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            }
        }, "mibsdk-net-resolve");
        t.setDaemon(true);
        t.start();
        synchronized (lock) {
            while (!done[0]) {
                long left = deadline - clock.uptimeMs();
                if (left <= 0) {
                    t.interrupt();
                    throw new SocketTimeoutException("network: resolve timed out");
                }
                try {
                    lock.wait(Math.min(left, 1000));
                } catch (InterruptedException e) {
                    t.interrupt();
                    Thread.currentThread().interrupt();
                    throw new IOException("network: interrupted");
                }
            }
        }
        if (failure[0] != null) {
            throw failure[0];
        }
        return result[0];
    }
}
