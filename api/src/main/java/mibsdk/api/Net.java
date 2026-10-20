package mibsdk.api;

/** Loopback-only TCP queries. Obtained from {@link PluginContext#net()}; needs {@link Permissions#NETWORK}. */
public interface Net {

    int MAX_RESPONSE_BYTES = 8192;
    int MAX_TIMEOUT_MS = 3000;

    /**
     * 127.0.0.1/localhost only. Connects, writes request (if non-null) plus "\n", reads to EOF;
     * at most 8 KiB; timeout capped at 3000 ms.
     */
    String query(String host, int port, String request, int timeoutMs) throws java.io.IOException;
}
