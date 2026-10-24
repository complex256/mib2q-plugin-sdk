package mibsdk.api;

/** Loopback-only TCP queries. Obtained from {@link PluginContext#net()}; needs {@link Permissions#NETWORK}. */
public interface Net {

    int MAX_RESPONSE_BYTES = 8192;
    int MAX_TIMEOUT_MS = 3000;

    /**
     * 127.0.0.1/localhost only. Connects, writes request (if non-null) plus "\n", reads to EOF;
     * at most 8 KiB; timeout capped at 3000 ms. The request is at most 2048 UTF-8 bytes (more throws
     * {@link java.io.IOException}). Any loopback port is reachable. A server that keeps the connection open
     * after sending 8 KiB or more yields a {@link java.net.SocketTimeoutException} rather than a truncated
     * result, because the read runs to EOF.
     */
    String query(String host, int port, String request, int timeoutMs) throws java.io.IOException;
}
