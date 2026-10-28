# Logging

[한국어](../logging.md) | [API index](README.md)

NaruServe provides two kinds of logs:

| Kind | Content | Express counterpart |
|---|---|---|
| Access log `AccessLog` | Method, path, status, duration, byte counts and request ID | `morgan` |
| Application log `Logger` | Application and framework events | `console.log` |

## Access logs

```java
app.accessLog(options -> {
    options.enabled(true);
    options.level(LogLevel.INFO);
    options.includeRequestId(true);
    options.includeDuration(true);
    options.includeBytes(true);
    options.exclude("/health");
    options.exclude("/favicon.ico");
});
```

| Option | Meaning |
|---|---|
| `enabled(boolean)` | Enable or disable access logs |
| `level(LogLevel)` | Logging level |
| `includeRequestId(boolean)` | Include the request ID |
| `includeDuration(boolean)` | Include elapsed time |
| `includeBytes(boolean)` | Include received and sent body bytes |
| `exclude(path)` | Exclude an exact path |

Defaults: enabled, `INFO`, request ID, duration and bytes included, no excluded paths. These defaults retain useful diagnostic information from the first request.

Repeated `accessLog(...)` calls update the same application's options in order. `listen()` freezes them. `exclude(path)` matches `req.rawPath()` exactly, including case, with no query string.

### Line format

Each request produces one line: `<method> <path> <status>[ aborted][ <n>ms][ in=<i> out=<o>][ rid=<id>]`. Optional fields appear when enabled; `aborted` appears when the response aborts. Field order stays fixed for text processing.

```text
GET /api/users/42 200 3ms in=0 out=27 rid=000000005f2c9a1e
POST /upload 413 aborted 12ms in=1048576 out=0 rid=000000005f2c9a1f
```

- `<path>` is the original path without its query string.
- `in` and `out` count body bytes actually read or written, excluding headers.
- `rid` identifies the request and matches a retained standard JSON error envelope's `requestId` when that explicit error representation is used. Default common HTTP HTML errors do not contain a `requestId` field; correlate them with the access/application log instead.

### When a line is written

| Request kind | Time |
|---|---|
| Ordinary HTTP | When the response finishes or aborts |
| WebSocket | One line at upgrade (`101`), then one close line with code and connection duration |
| SSE | One line when the stream finishes or fails; no initial line |

A WebSocket handshake line uses the HTTP format (`GET /ws 101 2ms in=0 out=0 rid=000000005f2c9a20`). It is written after `101` and before reading the first frame. The close line is `WS <path> <closeCode>[ <n>ms][ rid=<id>]`, with the same request ID and the duration from open to close. Codes include `1000` for `ws.close()`, `1001` for shutdown, `1008` for send queue overflow, the peer's code for a peer close, and `1006` for disconnect without a close frame. Rejected upgrades (`403`, `426`, `503`) produce only the ordinary HTTP line.

SSE appends ` sse start=<epochMillis> cause=<client|app|overflow|shutdown|error>` to the request line:

```text
GET /events 200 30512ms in=0 out=812 rid=000000005f2c9a21 sse start=1760918400000 cause=client
```

Duration covers the whole stream; `out` counts streamed bytes; `start` is epoch milliseconds. The first recorded termination cause wins: `app` for `close()`, `overflow` for queue overflow, `shutdown` for server shutdown, `client` for a peer disconnect detected during a write, and `error` otherwise. `HEAD` produces an ordinary line without the SSE suffix. Exclusions, enablement and log level also apply to these lines.

### Data excluded from access logs

- `Authorization` and `Cookie` headers
- Passwords and tokens
- Request/response bodies and uploaded file contents
- Full query strings

Newlines and similar characters in logged values are escaped to prevent log forgery.

### Common HTTP error records

Ordered error handlers do not suppress the original failure record. An error whose internal `StandardErrors.statusOf` classification is 500 is passed once per request and `Throwable` identity to `logger.error(message, throwable)`, including an error that is handled later or occurs after a successful response. Re-propagating the same object does not duplicate that record; distinct server failures may each be recorded. Client errors are not promoted to ERROR merely because the default body is HTML. The response remains single-commit.

Access logging and request cleanup are separate. A returned pending `Deferred` can keep input/uploads alive after the response's one access line has been written; settlement does not add another line. Existing access-log privacy exclusions remain unchanged. Exception messages and Java stack details in application logs/default error HTML are not an automatic scrubber for application-supplied secrets.

The module coordinates and common HTTP behavior below target NaruServe 0.10.0. See [installation](README.md) for availability and source builds.

## Application logger

```java
app.logger().info("Server initialized");
app.logger().error("Upload failed", new IOException("disk full"));
```

```java
public interface Logger {
    void trace(String m);
    void debug(String m);
    void info(String m);
    void warn(String m);
    void error(String m);
    void error(String m, Throwable t);
}
```

`ctx.logger()` inside `onStart` or `onStop` returns the same logger.

`RestApp.builder()` defaults to `ConsoleLogger` when you omit `logger(...)`. Its format is `<timestamp> <LEVEL> <message>`, with timestamp pattern `yyyy-MM-dd'T'HH:mm:ss.SSSZ`. The default level is `INFO`. `TRACE`, `DEBUG` and `INFO` write to `System.out`; `WARN` and `ERROR` write to `System.err`. Set a different threshold with `new ConsoleLogger(LogLevel.DEBUG)`.

Use `ConsoleLogger.format(level, message)` in a custom logger to obtain the same line without a trailing newline. It reads the default time zone on each call. The rotating file logger uses it and appends a stack trace when present. Express has no direct equivalent; a custom `morgan` formatter is the closest comparison.

```java
String line = ConsoleLogger.format(LogLevel.WARN, "disk almost full");
```

```kotlin
val line = ConsoleLogger.format(LogLevel.WARN, "disk almost full")
```

### Replace the logger

Implement `Logger` and pass it to `app.logger(customLogger)`.

| Environment/module | Default | Other choices |
|---|---|---|
| Android (`NaruServe-android`) | Logcat | File logger or custom `Logger` |
| JVM (`NaruServe`) | Console | SLF4J adapter, rotating file logger |

`NaruServe-api` has no SLF4J or `android.*` dependency. Logger implementations live in separate modules:

| Logger | Module | Coordinates | Android |
|---|---|---|---|
| Logcat | `NaruServe-logcat` | `com.snoworca.naruserve:naruserve-logcat:0.10.0` | API 21+. Builds without the Android SDK and calls `android.util.Log` by reflection |
| Rotating file | `NaruServe-filelog` | `com.snoworca.naruserve:naruserve-filelog:0.10.0` | API 21+ |
| SLF4J adapter | `NaruServe-slf4j` | `com.snoworca.naruserve:naruserve-slf4j:0.10.0` (`org.slf4j:slf4j-api:1.7.36`) | JVM only |

The general `NaruServe` bootstrap uses the console and does not include `NaruServe-logcat`. Android applications use `NaruServe-android`, which brings in Logcat and makes `RestApp.create()` choose it. Add the corresponding module when referencing a logger class directly. Logging failures do not stop HTTP service.

### Logcat

`new LogcatLogger()` uses tag `NaruServe`; `new LogcatLogger("MyServer")` sets another tag. Construction on a non-Android JVM throws `IllegalStateException`.

### Rotating file logger

`RotatingFileLogger.builder()` defaults to `./logs`, 5 MiB (5242880 bytes) per file, five files, a queue of 1024 entries and level `INFO`. The active file is `naruserve.log`; rotation moves it through `.1` to `.4` and deletes the oldest backup.

Each line uses the console format. A separate thread writes to disk. Queue overflow or a write failure (including an `Error` such as `OutOfMemoryError`) drops that line, increments `droppedCount()` and allows subsequent lines to proceed.

If renaming an active file or backup fails, rotation stops before overwriting `naruserve.log.1`; existing lines remain and writing continues in the same file. `rotationFailureCount()` records the failure. Another rotation attempt happens after the active file grows by another `maxFileBytes`. The oldest backup is deleted only when no backup slot is free, so repeated failures do not keep removing backups. The active file can exceed `maxFileBytes` during failures: check directory permissions and disk space when `rotationFailureCount()` is nonzero.

On Android, pass a writable directory such as `context.getFilesDir()` rather than the default working directory. The module has no `android.*` dependency and therefore does not choose an Android-specific directory:

```java
RotatingFileLogger logger = RotatingFileLogger.builder()
    .directory(new File(context.getFilesDir(), "logs"))
    .build();
```

### Shutdown and logger ownership

Near the end of `shutdown(...)` or startup-failure cleanup, just before releasing the HTTP adapter, NaruServe calls `flush()` when the logger is `Flushable`, then `close()` when it is `AutoCloseable`, to preserve queued file log entries.

The file logger's `flush()` and `close()` wait at most five seconds for a blocked writer, such as an unresponsive NFS mount. After that, queued lines count as dropped and the call returns. `ConsoleLogger.close()` only flushes `System.out` and `System.err`; it does not close them.

When several applications share one logger, the first application's shutdown closes it. Give each application a delegating logger that does not implement `AutoCloseable` when the shared instance must outlive them.
