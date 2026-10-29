# Networking

Plugins can talk to services on the unit, and — with an extra permission — to hosts outside it. The API is
plain TCP; there is no HTTP client and no TLS stack on the car.

## Query a service

```java
if (ctx.granted(Permissions.NETWORK)) {
    try {
        String reply = ctx.net().query("127.0.0.1", 8080, "status", 1000);
        ctx.log().info("daemon says " + reply);
    } catch (IOException e) {
        ctx.log().warn("daemon did not answer: " + e.getMessage());
    }
}
```

| Rule | Value |
|---|---|
| Loopback | `127.0.0.1` or `localhost`, with `network` |
| Remote host | Any host name or IP, with `network` **and** `network.outbound` |
| Request | At most 2048 UTF-8 bytes; a newline is appended |
| Response | At most 8192 bytes, read to EOF |
| Timeout | At most 3000 ms, including DNS resolution |
| Transport | Plain TCP. No TLS |

Any port is reachable. A request longer than 2048 bytes throws `IOException`. A server that keeps the
connection open after sending 8 KiB or more produces a `SocketTimeoutException` rather than a truncated
result, because the read runs to EOF.

## Reaching outside the unit

Outbound access is a second, separately approved permission:

```json
"permissions": ["network", "network.outbound"]
```

```java
String reply = ctx.net().query("api.example.com", 80, "GET /status HTTP/1.0\r\nHost: api.example.com\r\n", 2000);
```

The unit has no TLS stack (`javax.net.ssl` is not in its class library), so `https://` is not available
from a plugin. Options:

- use a plain-HTTP endpoint on your own network;
- run a small local proxy on the unit that terminates TLS and expose it on loopback;
- keep the plugin on loopback and let another process do the outbound request.

The SDK does not ship an HTTP client. If you need one, build the request string yourself, or add a local
daemon that speaks a simple protocol.

## Patterns

### Poll a daemon on a schedule

```java
Subscription poll = ctx.scheduler().every(30_000, () -> {
    try {
        String state = ctx.net().query("127.0.0.1", 8099, "tailscale status", 1000);
        status.set(state);
    } catch (IOException e) {
        status.set("offline");
    }
});
```

`every` runs on the plugin's worker thread. A poll still delays other callbacks, so keep the timeout
short and do not overlap polls.

### Query once on start

```java
ctx.scheduler().after(0, () -> {
    try {
        String version = ctx.net().query("127.0.0.1", 8099, "version", 500);
        ctx.log().info("daemon " + version.trim());
    } catch (IOException e) {
        ctx.log().warn("daemon unavailable");
    }
});
```

## What you cannot do

- Open a listening socket.
- Use `java.net.Socket`, `ServerSocket`, `URL.openConnection` or `InetAddress.getByName` directly. The
  build rejects them unless you declare `platform.unrestricted`.
- Use `https://` or any TLS API. The car has no JSSE.
- Use an HTTP client library; it is not on the car's class library.

## Permissions

| Permission | Approval text |
|---|---|
| `network` | Query local services on this unit (loopback only) |
| `network.outbound` | Connect to hosts outside this unit |

A plugin that declares a permission but is not granted it is `BLOCKED_PERMISSIONS` and does not start.
A plugin with `network` but without `network.outbound` can still use loopback; a remote call throws
`PermissionDeniedException` naming `network.outbound`.

The build cannot tell which host a call will use, so it checks only `network` at build time. Declare
`network.outbound` when you use a non-loopback host.

## Related

- [Storage and timers](storage-and-timers.md)
- [Permissions](../reference/permissions.md)
- [Java compatibility](../reference/compatibility.md)
