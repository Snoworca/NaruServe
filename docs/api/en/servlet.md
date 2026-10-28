# Servlet module (`NaruServe-servlet`)

[한국어](../servlet.md) | [English](servlet.md)

> **Version scope:** This page describes the 0.10.0 target checkout, not a Maven publication announcement. Dependency examples keep the current project version `0.9.0` until the release version changes; use the target checkout build for the behavior below.

`NaruServe-servlet` runs a NaruServe application inside a Servlet container such as Tomcat or Jetty. The container accepts connections instead of the embedded engine; route, middleware and `req`/`res` code remain the same.

> This adapter is optional. It has been checked on Jetty 9.4; other containers have not been verified.

## Requirements

- Servlet **3.1 or later** using `javax.servlet`.
- JVM only; not for Android.
- v1 has no `jakarta.servlet` module and does not support Servlet 5+ containers using that namespace. A Jakarta adapter is a post-v1 item.

```groovy
// Dependencies for a web application running in a Servlet container.
implementation 'com.snoworca.naruserve:naruserve-servlet:0.9.0'
// JSON codec; otherwise configure Builder.jsonCodec(...) yourself.
implementation 'com.snoworca.naruserve:naruserve-gson:0.9.0'
// The container provides the Servlet API.
compileOnly 'javax.servlet:javax.servlet-api:3.1.0'
```

## Registration

The following snippets configure a web application. Import `com.snoworca.naruserve.RestApp` and `java.util.Collections` in the Java example.

Provide one class implementing `ServletConfigurer`, then set the context init parameter `naruserve.configurer` to its class name. `NaruServeInitializer` handles the rest:

- The container discovers the initializer through `META-INF/services/javax.servlet.ServletContainerInitializer`. No servlet class entry in `web.xml` or classpath scan is required.
- If `naruserve.configurer` is absent, the initializer does nothing, so the JAR can safely be present in an application that does not use NaruServe.
- The configurer must be public with a public no-argument constructor. Its `RestApp.Builder` already contains a `ServletAdapter`. Select codec/logger options, build the app, register routes and return it.
- The servlet is registered as `naruserve`, with `asyncSupported=true`, without `MultipartConfig`, at `naruserve.urlPattern`.

| Init parameter | Default | Meaning |
|---|---|---|
| `naruserve.configurer` | None | Name of the `ServletConfigurer` implementation; absent means no startup |
| `naruserve.urlPattern` | `/*` | Only `/*`, `/`, or `/prefix/*` mappings are accepted |
| `naruserve.shutdownTimeoutSeconds` | `10` | Shutdown timeout in seconds; integer 1–300 |

Invalid values fail context startup with a `ServletException` that names the parameter and value.

`web.xml`:

```xml
<web-app xmlns="http://xmlns.jcp.org/xml/ns/javaee" version="3.1">
  <context-param>
    <param-name>naruserve.configurer</param-name>
    <param-value>com.example.AppConfigurer</param-value>
  </context-param>
  <context-param>
    <param-name>naruserve.urlPattern</param-name>
    <param-value>/api/*</param-value>
  </context-param>
</web-app>
```

Java configurer:

```java
import com.snoworca.naruserve.servlet.ServletConfigurer;

public class AppConfigurer implements ServletConfigurer {
    @Override
    public RestApp configure(RestApp.Builder builder) {
        RestApp app = builder.build();
        app.get("/users/:id", (req, res) -> res.json(Collections.singletonMap("id", req.param("id"))));
        return app;
    }
}
```

Kotlin:

```kotlin
// import com.snoworca.naruserve.servlet.ServletConfigurer
class AppConfigurer : ServletConfigurer {
    override fun configure(builder: RestApp.Builder): RestApp {
        val app = builder.build()
        app.get("/users/:id") { req, res -> res.json(mapOf("id" to req.param("id"))) }
        return app
    }
}
```

Express has no servlet deployment mechanism. The closest analogy is passing an application to an existing host server with `http.createServer(app)`; here, the container hosts the app.

## Differences from the embedded engine

| Item | Embedded engine (`app.listen`) | Servlet module |
|---|---|---|
| Port, TLS and thread pool | NaruServe settings | Container settings; `ServerHandle.port()` returns `0` |
| Startup/shutdown | `app.listen()` / `app.shutdown()` | Container startup calls `app.listen(0)`; undeployment calls `app.shutdown(timeout, SECONDS)` |
| Paths | Original request path | Original `getRequestURI()` minus context path and mapping prefix, preserving percent encoding |
| Uploads | NaruServe parser | Same parser over `getInputStream()`; no Servlet `Part` API or `MultipartConfig` |
| Deferred HTTP / SSE | Late callbacks and retained input | Deferred HTTP uses `AsyncContext`; SSE uses bounded adapter writer threads and sets async timeout 0 |
| Forced SSE termination | Close socket at shutdown deadline | `AsyncContext.complete()` at deadline; container determines when blocked writes unblock |
| WebSocket | Supported | Not supported by this adapter; upgrade to an `app.ws` route receives `501` |
| Response-header order | Write order | Container-dependent |
| Total thread bound | 452 (design 451 plus keeper) | Not applicable |
| Verified container | — | Jetty 9.4.58 / Servlet 3.1 only |

## Port and lifecycle

- The container chooses the port. `ServletAdapter.bind` retains the handler without opening sockets or threads. `ServerHandle.port()` is always `0`; `ServerHandle.address()` is not the container connector address. Read actual endpoints from container configuration.
- Before serving the first request, startup constructs the app with the configurer and calls `app.listen(0)`. `onStart` runs once and state becomes `RUNNING`. An explicit application call to `app.listen(8080)` takes the same adapter path and opens no listener. This resembles handing an Express app to an existing server instead of using its own listener.
- Failed `onStart` fails context startup with `ServletException` caused by `AppStartException`, leaving no application threads.
- During destroy, a `ServletContextListener` calls `app.shutdown(timeout, SECONDS)` once; the timeout comes from `naruserve.shutdownTimeoutSeconds` (10 by default). `onStop` runs, state reaches `TERMINATED`, and a second shutdown notification is harmless.
- The configurer can set `Builder.logger(...)`; otherwise the builder defaults to `ConsoleLogger`.

## Shutdown

The overall sequence matches the embedded engine's [shutdown](app.md#shutdown). Adapter responsibilities:

- Once shutdown starts, new requests receive `503`, `Connection: close`, the standard error body and request ID before reaching the core handler.
- The core sends the final SSE `event: shutdown` notification and closes streams. The adapter waits for active requests and streams until the deadline, returning early if all finish.
- At the deadline, returned deferred requests and retained completed responses are aborted and their transport holds are revoked; `AsyncContext` completes once. Active container callbacks are not interrupted and cannot resurrect a stopped exchange on return. Remaining SSE drains are cancelled and the executor is stopped; a running drain keeps its pool slot until it actually exits.
- Finally the handler is detached; subsequent requests receive `503`. No `naruserve-servlet-sse-*` threads remain within two seconds after shutdown finishes under the verified container behavior.

The promise to force-disconnect SSE at the deadline depends on the container's write timeout. `AsyncContext.complete()` terminates a stream blocked on a nonreading peer, but the container decides when the blocked write returns. Jetty 9.4 unblocks promptly; other containers may wait for their own write timeout.

## JSON codec

If the web application classpath contains `com.snoworca.naruserve.gson.GsonJsonCodec` (`NaruServe-gson`), the initializer finds it by reflection through the web application's classloader and installs it before calling the configurer. Override it with `Builder.jsonCodec(...)`. If neither supplies a codec, startup fails with `ServletException` mentioning `com.snoworca.naruserve.gson.GsonJsonCodec` and `jsonCodec`. The adapter's NaruServe module dependency is `NaruServe-api`.

## Configuration

The container owns connections, threads and virtual hosts. The connection-level embedded-engine `ServerOptions` listed below have no effect here, while `maxSseStreams` still applies. The `listen` port argument is ignored.

- Ignored: `bindAddress`, `allowedHosts`, `maxHeaderSize`, `maxHeaderCount`, `maxRequestLineLength`, `headerTimeout`, `bodyReadTimeout`, `writeTimeout`, `minBodyRate`, `maxWorkers`, `maxConnections`, `maxUpgradedConnections`, `maxConnectionsPerAddress`.
- Applied: `jsonLimit`, `formLimit`, `app.multipart`, `maxSseStreams`, `app.sse`, `trustProxy`.

The core enforces body limits. Bodies over the JSON/form/multipart limit receive `413` through the common error path (HTML by default); the adapter does not add container-specific limits. Configure Host restrictions using the container's virtual hosts because `allowedHosts` is ignored. LAN warnings and `ServerHandle.address()` describe the configured `bindAddress`, not the real connector; the default `127.0.0.1` therefore produces no LAN warning even if the container listens elsewhere.

## Threads

The embedded engine's total of 452 (design 451 plus `naruserve-keeper`) does not apply. This adapter uses container threads plus at most `maxSseStreams` SSE threads.

## Path handling

`req.rawPath()` uses original `getRequestURI()` with the context and mapping prefix removed, rather than the container-decoded `getServletPath()` or `getPathInfo()`. This resembles the path seen by an Express router mounted with `app.use('/api', router)`.

- Percent encoding stays intact during common HTTP path matching. Only participating captures decode once with strict UTF-8; malformed captures enter ordered error handling with `400`. Path parameters such as `;name=value` remain in the path.
- With context `/app` and mapping `/api/*`, `/app/api/users/%41;v=1` becomes `/users/%41;v=1`; `/users/:id` sees `id` equal to `A;v=1`.
- For `/*` and `/`, everything after the context is kept. An empty remainder becomes `/`.
- If the original URI does not start with context + prefix at a `/` segment boundary, such as `/app/%61pi/users`, the adapter returns standard `404`. Before startup or during shutdown it returns `503` and `Connection: close`.
- The adapter snapshots original query, method, protocol, remote address, secure flag and headers before the container can recycle them. Repeated header values appear once in per-name order, including mixed-case names; Servlet does not expose order between different names. The application original URL starts after context/mapping-prefix removal and stays stable across core router mounts.
- `getParameter*` is not called because it would consume the form body first. Input stays lazy; metadata capture never calls `getInputStream()` or prefetches the body.

The following observations use Jetty **9.4.58** / Servlet **3.1** with default container compliance and common GET routes `/a`, `/b`, `/a/b`, `/a/:x`, `/:x/a`. The routes return `reached ` plus the original raw pathname. `404` on an unmatched malformed-looking path does not imply global validation; a participating malformed capture is separately tested below. Container-rejected rows never dispatch to application middleware. These results do not override strict static/WS validation.

| Request | Case | Status | Response source / result |
|---|---|---:|---|
| `GET /a%zz` | Invalid percent encoding | 400 | Container |
| `GET /a%2` | Truncated percent escape | 400 | Container |
| `GET /a%C3%28` | No matching capture | 404 | Common HTML: `Cannot GET /a%C3%28` |
| `GET /a%2Fb` | No matching route | 404 | Common HTML: `Cannot GET /a%2Fb` |
| `GET /a%00b` | Encoded NUL | 400 | Container |
| `GET /a<NUL>b` | Raw NUL byte | 400 | Container |
| `GET /./a` | Raw dot segment matches `/:x/a` | 200 | Application: `reached /./a` |
| `GET /../a` | Dot-dot above root | 400 | Container |
| `GET /a/../b` | No matching raw route | 404 | Common HTML: `Cannot GET /a/../b` |
| `GET /%2e/a` | Encoded dot capture | 200 | Application: `reached /%2e/a` |
| `GET /%2e%2e/a` | Encoded dot-dot above root | 400 | Container |
| `GET /a/%2e%2e/b` | No matching raw route | 404 | Common HTML: `Cannot GET /a/%2e%2e/b` |
| `OPTIONS *` | Not an application pathname | 404 | Adapter standard JSON; mapping requires a leading `/` |

With an additional `/capture/:x` route that sends `req.param("x")`, `GET /capture/a%2Fb` returns `200` and `a/b`; `GET /capture/a%C3%28` returns common HTML `400` containing `PathDecodeException` and the failed capture. `GET /a?x=%2F&v=1` still routes on `/a`, preserving the original query. Direct retained strict `routing.Router` rejects all twelve historical unsafe paths; ordinary common matching does not inherit that global rejection. Other containers or compliance settings can reject earlier and need their own verification.

## Deferred completion and retained input

An unfinished response remains pending when its initial callback returns. The adapter establishes `AsyncContext` before the servlet returns; a later `Next`, response write, stream or SSE registration can proceed while the app is running. Deferred HTTP retains the container's async timeout; starting SSE sets it to zero. The servlet and all preceding filters must support async.

A pending `Deferred` returned from `methodAsync`/`useAsync` holds request work even after `res.send(...)` finishes the wire response. The adapter keeps the container request alive until core input/upload cleanup and work release; the first body read can therefore still decode the original POST. Request metadata is an immutable snapshot, not data from a recycled subsequent request. Settle the producer only after its final resource access. Completing a response does not implicitly settle a `Deferred` or run `Next`.

Normal completion releases the context/admission exactly once after the last hold. Detected disconnect/I/O failure, async timeout or forced shutdown can revoke transport holds to meet deadlines; late releases are harmless and cancelled input cannot reopen. Input/upload cleanup still respects an active core callback's ownership. Detection of an idle peer close without I/O is not immediate.

If async is unavailable, an unstarted pending response receives the existing adapter JSON `500`, then terminal cancellation releases core work, original input and upload files. If the response already sent `200 ack` but retained work remains, the adapter aborts and revokes that work without sending a second response. A synchronous completed request with no remaining hold needs no async support. Restore `asyncSupported=true` on the servlet and every preceding filter when using deferred work.

## Writing responses

- Status uses `setStatus`, headers use `addHeader` in application order, and the core's `Content-Length` uses `setHeader`. Neither `sendError` nor `setContentLength` is called, so container error pages do not replace `404` or `500` responses.
- Wire header order is container-dependent. Do not rely on order between names.
- A container may buffer a small body and add its length. An unknown-length `res.stream` larger than that buffer is chunked. Jetty rewrites `Content-Type` by removing the space after `;`.
- `HEAD` includes the GET body length without its body; `204` and `304` have no body.
- The container controls disconnection. For an aborted non-SSE response, the servlet throws `IOException` after the handler returns. The container closes a committed response without a final chunk, or returns 500 before commit. Writes after `abort` fail with `IOException` before execution. `abort` waits up to 250 ms for an active write before completing `AsyncContext`, preventing completion from racing with an ordinary active write. A socket write blocked longer than that ends through completion or the container's write timeout.

Application code needs no `javax.servlet` types. `Cookie` is NaruServe's type, not `javax.servlet.http.Cookie`.

## Uploads and form bodies

- Multipart, urlencoded and JSON bodies use the same NaruServe parsers over `getInputStream()`. `req.file`, `req.files`, `req.form` and `req.parts` match the embedded and in-memory adapters.
- The adapter does not call `getParts()`, `getPart()` or `getParameter*` and registers without `MultipartConfig`. The container does not preconsume the body or create upload temp files.
- Exceeding `app.multipart` file/part/request limits, `formLimit` or `jsonLimit` returns recoverable `413` through the common error path, HTML by default. Temp files are removed when request work and input ownership permit cleanup; a response ending early does not delete files still owned by a pending producer. Client-disconnect cleanup completes after active request callbacks release ownership.

## SSE

- `res.sse()` starts asynchronous processing with `startAsync()` and `AsyncContext.setTimeout(0)`, avoiding the container's default async timeout (30 seconds on Jetty 9.4). The servlet and any preceding filters must have `asyncSupported=true`.
- The adapter enforces `maxSseStreams` (default 128). At capacity, a common HTTP `res.sse()` failure enters error handling with `503` before starting the response (HTML by default). A running drain keeps its slot through completion or abort until it actually exits; setup failures and never-started cancellations release it once.
- SSE may start after the initial servlet invocation returns; it reuses the existing async context and bounded writer ownership.
- Adapter daemon threads named `naruserve-servlet-sse-<n>` run the loops, up to `maxSseStreams`. The container request thread returns as soon as the handler finishes.
- Events and `: ping` heartbeats flush on every write. Events sent by the handler before the loop starts also write immediately.
- Completion calls `AsyncContext.complete()` once and invokes `onClose` once. A peer socket close is detected on the next write or heartbeat and logs `cause=client`.
- See [shutdown](#shutdown) for deadline behavior and the container-dependent exception.

## WebSocket

Servlet WebSocket is unsupported by this adapter. A valid upgrade to `app.ws` receives `501`; use the embedded engine for WebSocket.

- A valid request (`Upgrade: websocket`, `Connection: Upgrade`, version 13 and a key) receives standard `501` with request ID, never `101` or `500`; no session is created.
- Core checks still apply first: ordinary GET/HEAD requests without Upgrade on a WebSocket-only path receive `426` with `Upgrade: websocket`; disallowed Origin receives `403`; non-GET methods behave as with the embedded engine.

## Application configuration

Configure routes exactly as with the embedded engine. Instead of calling `listen` yourself, return the app from the configurer for container startup:

```java
// Same application configuration as with the embedded engine
static void configure(RestApp app) {
    app.onStart(ctx -> ctx.register(UserService.class, new UserService()));
    app.get("/api/users", (req, res) -> res.json(req.service(UserService.class).findAll()));
}
```
