package mibsdk.api;

/**
 * TCP queries from the unit. Obtain it from {@link PluginContext#net()}; needs {@link Permissions#NETWORK}.
 *
 * <p>Loopback hosts ({@code 127.0.0.1} and {@code localhost}) are always allowed with the
 * {@code network} permission. Any other host also needs {@link Permissions#NETWORK_OUTBOUND}. The API is
 * plain TCP: the car's Java 1.4 library has no TLS stack, so use a local proxy if you need HTTPS.
 *
 */
public interface Net {

    /** The largest response, in bytes. */
    int MAX_RESPONSE_BYTES = 8192;
    /** The largest timeout, in milliseconds. */
    int MAX_TIMEOUT_MS = 3000;

    /**
     * Connects to a TCP service, writes the request and reads until EOF.
     *
     * <p>Loopback needs {@code network}; a remote host also needs {@code network.outbound}. The request is
     * at most 2048 UTF-8 bytes; a newline is appended. The response is at most
     * {@value #MAX_RESPONSE_BYTES} bytes and the timeout is capped at {@value #MAX_TIMEOUT_MS} ms,
     * including DNS resolution. The read runs to EOF, so a server that keeps the connection open after
     * sending 8 KiB yields a {@link java.net.SocketTimeoutException} rather than a truncated result.
     *
     * @param host a loopback name, a host name or an IP address
     * @param port the port, 1–65535
     * @param request the request, or null for none
     * @param timeoutMs the timeout; capped at {@value #MAX_TIMEOUT_MS}
     * @return the response text
     * @throws java.io.IOException on connect, write, resolve, read or request-size failure
     * @throws PermissionDeniedException if the required permission is not granted
     * @since api 2
     */
    String query(String host, int port, String request, int timeoutMs) throws java.io.IOException;
}
