# Lifecycle and services

[한국어](../lifecycle.md) | [API index](README.md)

Express applications put shared objects such as database connections in module variables or `app.locals`. NaruServe creates and registers them once at startup, then lets HTTP, WebSocket and SSE handlers retrieve the same objects. This resembles application-scoped Servlet `ServletContext` storage.

There is no dependency injection or automatic scanning. Write registration and lookup explicitly.

## Example

```java
RestApp app = RestApp.create();

app.onStart(ctx -> {
    Database db = new Database();
    ctx.own(db);                // Register before connect(), so failure still closes db
    db.connect();

    ctx.register(Database.class, db);
    ctx.register(UserService.class, new UserService(db));
    ctx.attribute("appVersion", "1.0.0");
    ctx.logger().info("Services initialized");
});

app.get("/api/users/:id", (req, res) -> {
    UserService users = req.service(UserService.class);
    res.json(users.findById(req.param("id")));
});

app.ws("/ws", (req, ws) -> {
    UserService users = req.service(UserService.class);   // The same instance
    ws.onText(id -> ws.sendJson(users.findById(id)));
});

app.onStop(ctx -> ctx.logger().info("Server stopping"));

app.listen(8080);
```

Kotlin:

```kotlin
app.onStart { ctx ->
    val db = Database()
    ctx.own(db)
    db.connect()
    ctx.register(Database::class.java, db)
    ctx.register(UserService::class.java, UserService(db))
}

app.get("/api/users/:id") { req, res ->
    res.json(req.service(UserService::class.java).findById(req.param("id")))
}
```

`Database` and `UserService` are application-defined types. See the complete source examples linked below for imports and entry points.

## onStart / onStop

| Callback | When it runs | Work to perform |
|---|---|---|
| `onStart(ctx -> ...)` | Once inside `listen()`, before binding the port | Create services, call `ctx.own` and `ctx.register` |
| `onStop(ctx -> ...)` | Once during shutdown, before closing services | Application cleanup; services are still available |

- Multiple callbacks run in registration order.
- If `onStart` fails, no port is opened. Already owned/registered resources close in reverse order and `listen()` throws. Cleanup exceptions are attached with `getSuppressed()`.
- Unchecked startup exceptions propagate unchanged. Checked exceptions and startup interrupted by `shutdown()` are wrapped in unchecked `AppStartException`. `listen()` needs no `throws Exception` declaration in Java/Kotlin callers.
- `onStop` runs only if the app reached `RUNNING`, never after failed startup.
- An `onStop` exception logs at ERROR, then the remaining callbacks and cleanup run through `TERMINATED`. An `Error` such as `AssertionError` or `NoClassDefFoundError` also allows all cleanup, but the first `Error` is rethrown from `shutdown()` after termination. When shutdown runs on a separate thread because it was requested inside a handler, this becomes that thread's uncaught error.

## ApplicationContext

This is `ctx` in `onStart` / `onStop` and the object returned by `req.application()`. `@Nullable` below is `org.jspecify.annotations.Nullable`, corresponding to Kotlin `T?`.

```java
public interface ServiceRegistry {
    <T> void register(Class<T> type, T instance);          // Closes AutoCloseable on shutdown
    <T> void registerExternal(Class<T> type, T instance);  // Does not close
    <T> T get(Class<T> type);           // ServiceNotFoundException if absent
    <T> @Nullable T getOrNull(Class<T> type);     // null if absent
    boolean contains(Class<?> type);
}

public interface ApplicationContext extends ServiceRegistry {
    void attribute(String key, @Nullable Object value);   // null removes the key
    <T> @Nullable T attributeAs(String key, Class<T> type);
    void own(AutoCloseable resource);
    Logger logger();
}
```

`req.service(UserService.class)` is equivalent to `req.application().get(UserService.class)`.

### register

- Call only inside `onStart`. Once the app becomes `RUNNING`, the service registry is frozen, so request threads can read it concurrently. This freeze does not apply to ordinary common HTTP layers: route/middleware registration and existing RouteBuilder callbacks can be appended after listen. Server options, lifecycle callbacks and WebSocket configuration retain their separate startup guards.
- Registering the same `Class` twice is a configuration error; it never overwrites.
- Registering one object under its interface and implementation class is allowed; it closes only once.

```java
JdbcUserService impl = new JdbcUserService(db);
ctx.register(UserService.class, impl);
ctx.register(JdbcUserService.class, impl);   // Same instance, closed once
```

Use wrapper types when you need two services of the same type, such as two databases. Named `ServiceKey` support is a post-v1 consideration.

### own

`own` gives the framework ownership of an `AutoCloseable`, closing it at shutdown or startup failure. Call it **immediately after creation, before initialization**, so a failed initialization cannot leak the resource.

### Close order

Owned/registered `AutoCloseable` objects close in reverse registration order: `UserService` (if closeable), then `Database` in the example. A close failure does not stop subsequent cleanup.

Register objects owned elsewhere, such as a shared connection pool, through `registerExternal`. Lookup works normally, but NaruServe does not close them. A separate method makes ownership clear at the call site instead of hiding it in an `Ownership.EXTERNAL` argument:

```java
ctx.registerExternal(ConnectionPool.class, sharedPool);   // Not closed on shutdown
```

### attribute

This is an application-wide, thread-safe string-keyed store, corresponding to Express's `app.locals`. It remains writable during request handling:

```java
ctx.attribute("appVersion", "1.0.0");
String v = req.application().attributeAs("appVersion", String.class);
ctx.attribute("appVersion", null);   // Remove
```

`attribute(key, null)` removes without a cast. Use `removeAttribute(key)` for explicit removal and `attributeAs(key, type)` for typed reads. A stored value of the wrong type throws `ClassCastException`.

## Storage scopes

| Scope | Access | Lifetime | Express |
|---|---|---|---|
| Service | `ctx.register` / `req.service` | Startup to shutdown | Module variable |
| Application attribute | `ctx.attribute` / `req.application().attributeAs` | Startup to shutdown | `app.locals` |
| Request attribute | `req.attribute` / `req.attributeAs` | One request | `res.locals`, `req.xxx` |
| WebSocket session attribute | `ws.attribute` / `ws.attributeAs` | One connection | `ws.xxx` |

**All requests share service instances concurrently.** Make mutable service state thread-safe yourself; the framework does not lock it for you.

## States

Read the current state with `app.state()`:

```text
NEW --listen/start--> STARTING --success--> RUNNING --shutdown--> STOPPING --> TERMINATED
 |                        |
 |                        +--failure--> FAILED --cleanup--> TERMINATED
 +--shutdown-------------------------------------------------> TERMINATED
```

| State | Meaning |
|---|---|
| `NEW` | Initial configuration; server options, services and WebSocket configuration must respect startup guards |
| `STARTING` | Callbacks, validation and binding. Requests arriving after binding wait for `RUNNING`; if startup fails, they receive `503` and `Connection: close`. If stop was already requested, they receive `503` immediately |
| `RUNNING` | Serving requests |
| `STOPPING` | Reject new requests/upgrades with `503`, `Connection: close` and the standard error body |
| `FAILED` | Startup failed; cleanup proceeds to `TERMINATED` |
| `TERMINATED` | Finished; cannot restart |

Do not call `listen()` twice or concurrently. Create a new app after shutdown. Waiting during `STARTING` avoids rejecting a request that arrives immediately before `listen()` returns.

### Shutdown order

See [shutdown](app.md#shutdown) for the steps. WebSocket/SSE farewell notifications run for at most one second while connections are still accepted. This differs from the frozen design in `object.md` §11.4, which stops accepting before farewell notifications. NaruServe continues accepting during the farewell period, answers new requests/upgrades with `STOPPING`'s `503` and `Connection: close`, then stops accepting.

`shutdown(timeout, unit)` returns within the timeout plus three seconds when callbacks/resources return promptly: up to 1.5 seconds for TinyHttpd stopping, one second to release the adapter and 0.5 seconds margin. `onStop`, resource closes and logger flushing can extend that time; the lifecycle does not impose its own timeout on those calls.

### Shutdown during startup

Calling `shutdown(timeout, unit)` while `STARTING` records a stop request and waits for `listen()` to finish. Inside an `onStart` callback it returns immediately rather than waiting. If its timeout expires, it returns without throwing, but the stop request stays recorded. At the next checkpoint (before another callback, before binding, or before entering `RUNNING`), `listen()` aborts startup with `AppStartException`. Keeping the request prevents the application from unexpectedly becoming `RUNNING` after the stop caller has returned.

### Shutdown inside a request handler

A route or middleware calling `shutdown(timeout, unit)` starts shutdown on a separate thread and returns immediately. The response can finish normally. Waiting on the request thread would deadlock shutdown's wait for that very request until the timeout, then force-disconnect the response. Observe `app.state()` from another thread when you need confirmation of `TERMINATED`.

On keep-alive requests, TinyHttpd may wait about two seconds before closing that connection, delaying `TERMINATED`; this is a connection-close grace period separate from the shutdown timeout. A handler calling shutdown during an existing `STOPPING` also returns immediately. If the shutdown thread cannot be created, such as under memory pressure, cleanup runs on the request thread; it may wait for that request until timeout, but still reaches `TERMINATED`.

Calling shutdown from an SSE `onClose` listener also returns immediately and runs shutdown separately, because shutdown otherwise waits for that stream to terminate.

## Deferred callbacks and request resources

The 0.10.0 common HTTP checkout separates callback return, response completion and request cleanup. An ordinary callback that returns without responding or continuing leaves the request pending. A later callback can call `next.run()`, send JSON, or start a stream/SSE. Fulfillment does not implicitly continue or end a response.

Use `app.getAsync` or `app.methodAsync` when background work must retain request resources after an early response. Return that work's pending `Deferred`; creating one without returning it does not acquire a request lease. Schedule work with an executor owned by your application. No new executor or `CompletionStage` dependency is required by the core.

1. Create `Deferred.pending()`, arrange the producer, and return that result from the async callback.
2. While its lease is active, the producer may read the same request body or upload, even after an earlier `res.send("ack")`. Built-in Tiny/Servlet adapters retain unread input without eagerly reading it.
3. If no response was sent, explicitly send or end it. If an early response already finished, do not send a second response. Call `work.resolve()` only after the producer's last request/upload access; use `work.reject(error)` to enter the downstream error path. Rejection after commit cannot create a second error response.
4. Each result settles once. `onSettled(Runnable)` observes success or rejection once, including late registration; it does not continue or end the response. Normal cleanup waits for active callbacks, queued continuations and every pending lease. Save data needed beyond cleanup to application-owned storage.

Terminal abort, async timeout or forced shutdown can revoke pending ownership. Late settlement or continuation cannot reopen a cancelled input or recycle a completed exchange into a new request. An actually running callback/drain still owns its active resources until it exits; do not assume forced shutdown can stop arbitrary user code. A deferred reentry restores the request lifecycle context, so calling shutdown inside that callback does not wait on itself.

### Custom HTTP adapters

`HttpExchange.onAbort(Runnable)` and `Runnable retainRequest()` are additive defaults for existing adapters. The default abort hook validates a nonnull listener and otherwise does nothing; the default retention returns a no-op release action. Compatibility defaults alone do not give a custom adapter the built-in deferred transport guarantees.

An adapter that generates an error response outside the core can report its status and successfully written body-byte count through `responseStatus()` and `responseBytesWritten()`. Their defaults, `0` and `-1`, mean unknown, so logging uses the core's metrics. This includes the Servlet adapter's synthetic 500 for unsupported asynchronous configuration. Publish snapshots before terminal notification, without consulting a recycled container. Invalid values or observer failures neither block cleanup nor cause a second response write.

An adapter implementing retention must keep request input/container state valid until all normal holds are released, separately from `finish()` sending the response. Each returned release action must be thread-safe and idempotent. Implement detected abnormal abort notification once per registration, including late registration, outside adapter locks; listener failure must not skip cleanup. Normal `finish()` must not signal abort. Idle peer closure need not be detected before I/O. Terminal cancellation may revoke holds, with harmless late release and no resurrection; maintain bounded stop and preserve active I/O/drain ownership. Reuse `common.RequestRetentions`, `common.AbortListeners` and `common.SafeCallbacks` when implementing these contracts.

## Android

- Create and manage `RestApp` inside an Android `Service`.
- `RestApp.start(...)` includes `listen()`; **do not call it on the main thread**. Android does not install the JVM shutdown hook, so call `ServerHandle.shutdown(...)` or `app.shutdown(...)` from `Service.onDestroy`. See [start](app.md#start).
- **Do not call `listen()` or `shutdown()` on the main thread.** They perform network work; shutdown waits for requests then runs callbacks, resource closes and logger flushing, which may extend beyond the timeout.
- Forced process termination may skip both `onDestroy` and `onStop`. Persist important data while handling requests.
- Create a new `RestApp` when restarting the service.
- Your application handles background execution and foreground-service policy. The framework does not guarantee continuous server uptime.

The following Android `ServerService` is a documentation example only. The v1 build does not compile it because Android sample builds are out of v1 scope by the 2026-10-20 user decision:

```java
public class ServerService extends Service {
    private RestApp app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public void onCreate() {
        app = RestApp.create();
        app.get("/ping", (req, res) -> res.send("pong"));
        worker.execute(() -> {
            try {
                app.listen(8080);
            } catch (RuntimeException e) {   // Port in use, etc.; uncaught failure can kill the process
                Log.e("Server", "start failed", e);
            }
        });
    }

    @Override public void onDestroy() {
        worker.execute(() -> app.shutdown(10, TimeUnit.SECONDS));
        worker.shutdown();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
```

## First application

The [Java](../../../examples/java/src/main/java/example/JavaExample.java) and [Kotlin](../../../examples/kotlin/src/main/kotlin/example/KotlinExample.kt) examples include imports, `main` and shutdown hooks. Their shared services use `ConcurrentHashMap` and `AtomicInteger`. They prepare the upload directory before listening and fail startup if that fails. NaruServe closes response streams after transmission; do not close them early with try-with-resources or Kotlin `use`.
