# Response

[한국어](../response.md) | [API index](README.md)

Response corresponds to Express's res. status, header, addCookie and cache return Response for chaining. Examples use application-provided variables and services; see the [quick start](README.md#quick-start) for a complete server.

```java
res.status(201).header("X-Request-ID", id).json(created);
```

The common HTTP behavior below targets NaruServe 0.12.0.

## Method summary

### Status and headers

| Method | Returns | Express | Behavior |
|---|---|---|---|
| `status(code)` | Response | res.status | Default 200; outside 100..999 throws IllegalArgumentException |
| `header(name, value)` | Response | res.set | Replace case-insensitive same-name headers; invalid token name, controls except tab, or characters above U+00FF throw IllegalArgumentException |
| `append(name, value)` | Response | `res.append(name, value)` | Add a header line without replacing a same-name header, so headers such as Set-Cookie can go out as several lines; name and value get the header check; see [adding header lines](#adding-header-lines) |
| `cookie(name, value[, configurer])` | Response | res.cookie | Default Path=/; CookieOptions callback |
| `clearCookie(name[, configurer])` | Response | res.clearCookie | Max-Age=0 at matching Path/Domain |
| `addCookie(cookie)` | Response | res.cookie | Append Set-Cookie; see [cookies](cookie.md) |
| `cache(policy)` | Response | Set Cache-Control | Last value before commit wins; see [cache](static.md#cache-policy) |
| `location(url)` | Response | res.location | Reject empty URL or invalid header value |
| `type(value)` | Response | res.type | MIME string or extension such as png/.PNG; unknown extension uses application/octet-stream |
| `isCommitted()` | boolean | res.headersSent | Have status and headers been sent? |

Header values use ISO-8859-1, one byte per character. Names must be HTTP tokens: empty, whitespace or colon-containing names are invalid. CR/LF, NUL and other controls except tab are rejected.

### Send a body

| Method/value | Content-Type | Express | Behavior |
|---|---|---|---|
| `json(value)` | application/json; charset=UTF-8 | res.json | Encode through the configured JsonCodec |
| `send(Object)` with String | text/plain; charset=UTF-8, unless explicitly set | res.send(text) | Text |
| `html(String)` | text/html; charset=UTF-8 | res.send(html) | HTML |
| `send(Object)` with byte[] | Explicit type or application/octet-stream | res.send(buffer) | Binary |
| `stream(in, contentType)` | Argument | stream.pipe(res) | Commit headers now; defer an initial copy until the callback chain unwinds; a later call may copy on its calling thread; framework closes the input exactly once on success, failure or abort |
| `relay(in, contentLength)` | The app's value; none added or changed | None; in Express set Content-Length and `stream.pipe(res)` | Send a stream framed by its known length; owns the input as stream does; see [relaying a stream](#relaying-a-stream-with-its-length) |
| `file(File)` | File extension | res.sendFile | Range/conditional requests; no Content-Disposition |
| `download(File, filename)` | File extension | res.download | Content-Disposition: attachment |
| `end()` | — | res.end | No body |
| `redirect([status,] url)` | — | `res.redirect([status,] url)` | Default `302` with `Location`; only 301, 302, 303, 307 and 308 |
| `sendStatus(code)` | text/plain; charset=UTF-8 | `res.sendStatus(code)` | Status with its reason text; empty body for 204, 304 and HEAD |
| `sse()` | text/event-stream | — | Return SseClient; see [SSE](sse.md) |
| `abort(error)` | — | res.destroy | Abort connection; harmless after completion/abort |
| `onAbort(listener)` | — | `res.on('close', ...)` with a `!res.writableFinished` check | Register a listener that runs once when the response ends abnormally; returns void; see [abort notification](#abort-notification) |

Binary sends default to application/octet-stream as Express Buffer sends do. stream transfers input ownership; the caller must not close it before transmission.

### send dispatch

Dispatch uses the actual runtime value even from an Object variable: String becomes text, byte[] becomes binary, other objects/collections/numbers/booleans become JSON. `send(null)` ends without a body; use json(null) for a JSON null literal. Use html explicitly for HTML.

| Intent | Express | NaruServe |
|---|---|---|
| Text | res.send('ok') defaults to text/html | res.send("ok") defaults to text/plain |
| HTML | res.send('<b>ok</b>') | res.html("<b>ok</b>") |
| JSON object | res.send(object) or res.json | res.send(map) or res.json(map) |
| JSON string | res.json('hello') | res.json("hello") produces the quoted JSON string |

## Redirects and status responses

```java
res.redirect("/login");                    // 302, Location, empty body
res.redirect(307, "/login");               // method-preserving redirect
res.status(201).location("/users/7").send(user);
res.type("png").send(new byte[] {1, 2});    // image/png
res.sendStatus(404);                        // 404, plain-text Not Found
```

redirect accepts only 301, 302, 303, 307 and 308. It validates both status and URL before changing the response; normal header validation rejects CR/LF. sendStatus sends the shared reason phrase as text, or Error when no phrase exists. 204, 304 and HEAD have empty bodies. Like other writes, these methods throw after the response finishes.

## json

```java
res.json(user);                                        // POJO
res.json(Arrays.asList(user1, user2));                 // array
res.json(Collections.singletonMap("success", true));  // {"success":true}
res.json("Unauthorized");                              // "Unauthorized"
res.json(null);                                        // null
```

Kotlin:

```kotlin
res.json(mapOf("success" to true))
```

Encoding completes before commit. An encoding exception can become an error response only while the response remains uncommitted.

## Commit once

The response moves NEW → COMMITTED → COMPLETED, or COMMITTED → STREAMING → COMPLETED for streaming, and ABORTED on abnormal termination.

- After sending a body, changing status/headers/cookies or sending again throws IllegalStateException whose message includes the current state. isCommitted and request read methods remain callable, but request-owned resources still obey their lifetime rules.
- Returning without sending or continuing leaves common HTTP pending. Call `send`, `json`, `end` or another completion API explicitly, or retain the response for a later callback. `next.run()` continues; it does not itself end the response.
- Exceptions and `next.error(error)` enter downstream error layers in registration order. Before commit, the default final handler produces HTML with escaped Java `Throwable` details and stack frames, plus `Content-Security-Policy` and `X-Content-Type-Options`. Explicit JSON answers remain available through error middleware. After commit, an unhandled error aborts an unfinished response; an error after completion remains observable in logging without committing again.
- status accepts 100..999, matching Express 5's status range (its error is RangeError).
- 204, 304 and HEAD suppress bodies, including values passed to json.
- A later callback may send JSON or start a stream/SSE. Response completion and request-resource cleanup are separate: returned pending `Deferred` work can retain input and uploads after response bytes finish. See [request ownership](lifecycle.md#deferred-callbacks-and-request-resources).

## Files

```java
app.get("/report", (req, res) -> res.file(new File("/data/report.pdf")));

app.get("/export", (req, res) -> {
    res.cache(CachePolicy.noStore())
       .download(new File("/data/export.bin"), "export.bin");
});
```

- Large files stream without loading them into memory.
- A satisfiable single Range receives 206; an out-of-bounds start receives 416. Invalid or multiple ranges are ignored with a full 200. Conditional ETag/Last-Modified requests can return 304. See [ranges](static.md#range-and-conditional-requests).
- Do not construct file paths directly from user input: only staticFiles enforces a mount root.
- file/download reject a supplied path that is itself a symbolic link, missing, a directory or not a regular file with an HTTP 404 error entering the ordered error path; the unhandled common HTTP result is HTML.
- file leaves Content-Disposition unset so the browser may display it; download requests an attachment and sanitizes filename header-injection characters.

### Default cache policy

app.defaultCache(policy) supplies Cache-Control only if the response has none. Default null adds nothing; existing response or framework cache headers remain unchanged. A response written by `res.relay` never receives the `defaultCache` policy. See [server policy](static.md#default-server-policy).

```java
app.defaultCache(CachePolicy.noCache());
app.get("/me", (req, res) -> res.cache(CachePolicy.noStore()).json(Collections.singletonMap("id", 1)));
```

Kotlin:

```kotlin
app.defaultCache(CachePolicy.noCache())
app.get("/report") { req, res -> res.file(File("/data/report.pdf")) }
```

## Streams

`stream` commits status and headers; subsequent status/header calls throw, so set them first. An initial copy is deferred until the callback chain unwinds. Once that initial return point has passed, a subsequent call can perform the copy on its calling thread before returning, including inside a late callback. A late call can therefore block on I/O; there is no guarantee of one dispatch thread for all sends. NaruServe closes the input once. Do not wrap it in try-with-resources or Kotlin `use`, which can close it before transmission. Retaining request input through a returned pending `Deferred` does not transfer ownership of this response stream back to the application.

```java
app.get("/video", (req, res) -> {
    InputStream in = new FileInputStream(new File("/data/video.mp4"));
    res.stream(in, "video/mp4");   // NaruServe closes the stream
});
```

Kotlin:

```kotlin
app.get("/video") { req, res ->
    res.stream(FileInputStream(File("/data/video.mp4")), "video/mp4")
}
```

Express equivalent: fs.createReadStream(path).pipe(res).

## Adding header lines

`res.append(name, value)` adds a header line and returns the `Response`, as Express `res.append` does. It keeps an existing header of the same name, so headers such as `Set-Cookie` can go out as several lines. The name and value get the same name and value check as `res.header`: a name that is not an HTTP token, or a value with a control character such as CR/LF (tab excepted) or a character above U+00FF, throws `IllegalArgumentException`. After the response was sent it throws `IllegalStateException`, as `res.header` does. `res.header` keeps replacing a header of the same name (Express `res.set`). See [header checks](#header-checks).

```java
app.get("/login", (req, res) -> {
    res.append("Set-Cookie", "theme=dark; Path=/")
       .append("Set-Cookie", "lang=ko; Path=/")   // adds a second line
       .send("ok");
});
```

Kotlin:

```kotlin
app.get("/login") { req, res ->
    res.append("Set-Cookie", "theme=dark; Path=/")
        .append("Set-Cookie", "lang=ko; Path=/")
        .send("ok")
}
```

## Abort notification

`res.onAbort(listener)` registers a listener for a response that ends abnormally before it completed. It returns `void`, and a `null` listener throws `NullPointerException`.

- The listener runs once when the client disconnects or the app calls `res.abort`, and never for a response that completed normally.
- Should the response be aborted already, a listener registered after the abort runs at once (on the registering thread).
- The listener may run on a thread other than the request thread: the thread that called `res.abort`, or the adapter thread that detected the disconnect. Keep the state it touches thread-safe.
- An exception of one listener goes to the app logger, and the other listeners still run.
- NaruServe adds no disconnect detection of its own; an adapter may report a disconnect during idle time only at the next I/O.

Express counterpart: `res.on('close', () => { if (!res.writableFinished) { ... } })`. Express fires `close` after a normal finish too, hence the `!res.writableFinished` check; `onAbort` runs only for an abnormal end.

```java
ExecutorService workers = Executors.newCachedThreadPool();
app.get("/report", (req, res) -> {
    Future<?> job = workers.submit(() -> {
        Thread.sleep(5000);                    // a slow job
        res.send("report");
        return null;
    });
    res.onAbort(() -> job.cancel(true));       // a client that left cancels the job
});
```

Kotlin:

```kotlin
val workers = Executors.newCachedThreadPool()
app.get("/report") { req, res ->
    val job = workers.submit(Runnable {
        Thread.sleep(5000)
        res.send("report")
    })
    res.onAbort { job.cancel(true) }
}
```

Custom adapter limit: for `HttpExchange.onAbort` the SPI default checks its argument and does nothing. So with a custom `HttpAdapter` whose exchange does not override it, an `onAbort` listener may never be called when the client disconnects; a call to `res.abort` by the app still runs it. The TinyHttpd and servlet adapters report disconnects.

## Relaying a stream with its length

`res.relay(in, contentLength)` sends a stream framed by a given length. Use it when the app has set every header itself, as a proxy does when it passes on the status, headers and length another server sent. It differs from `res.stream(in, contentType)` as follows.

- It adds or changes no `Content-Type`: a value the app set goes out, and with none the response has none.
- It sends only the headers set before it besides `Content-Type` and `Content-Length`; a `Content-Length` or `Transfer-Encoding` the app set is dropped and `contentLength` sets the framing again.
- It applies no `app.defaultCache`.
- A length of 0 or more sends exactly `contentLength` bytes and aborts when the stream ends early (the connection closes); a longer stream is cut at that length.
- A length of `-1` sends with an undetermined length; the adapter picks the framing (such as chunked).
- A length below `-1` throws `IllegalArgumentException` and closes the input.
- `HEAD` requests and `304` responses keep a non-negative length as `Content-Length` without a body; `204` responses send neither body nor `Content-Length`.
- It owns the stream as `res.stream` does: NaruServe closes the input once on success, failure or abort, so the app must not close it first.

On a `304` response `res.relay` hands its length to the adapter, and the client sees that `Content-Length` only where the adapter writes it: TinyHttpd 3.1.1 omits it (allowed by RFC 9110 §8.6), and the servlet adapter hands it to the container; a `HEAD` response keeps it on every built-in adapter.

Express has no counterpart; the nearest idiom is `res.set('Content-Length', n)` followed by `upstream.pipe(res)`.

```java
app.get("/backup", (req, res) -> {
    File backup = new File("/data/backup.bin");
    res.type("application/octet-stream");
    res.relay(new FileInputStream(backup), backup.length());   // NaruServe closes the stream
});
```

Kotlin:

```kotlin
app.get("/backup") { req, res ->
    val backup = File("/data/backup.bin")
    res.type("application/octet-stream")
    res.relay(FileInputStream(backup), backup.length())
}
```

## Header checks

`HttpHeaderValues` in the `com.snoworca.naruserve.common` package exposes the header check that `res.header` and `res.append` apply: `HttpHeaderValues.isToken(name)` checks a name and `HttpHeaderValues.isFieldValue(value)` checks a value. Use them to check a value before sending and pick another response instead of an exception.

| Method | Checks | Result |
|---|---|---|
| `HttpHeaderValues.isToken(name)` | Header name (HTTP token) | `false` for an empty name or one holding whitespace or `:` |
| `HttpHeaderValues.isFieldValue(value)` | Header value | `true` for an empty string and for HTAB (tab); `false` for CR, LF, NUL, DEL and any character above U+00FF |

Express has none. The nearest are Node's `http.validateHeaderName` and `http.validateHeaderValue`, which throw instead of returning a boolean.

```java
import com.snoworca.naruserve.common.HttpHeaderValues;

app.get("/echo", (req, res) -> {
    String value = req.query("v");
    if (value == null || !HttpHeaderValues.isFieldValue(value)) {
        res.status(400).send("bad header value");   // 400 instead of an exception
        return;
    }
    res.header("X-Echo", value).send("ok");
});
```

Kotlin:

```kotlin
app.get("/echo") { req, res ->
    val value = req.query("v")
    if (value == null || !HttpHeaderValues.isFieldValue(value)) {
        res.status(400).send("bad header value")
    } else {
        res.header("X-Echo", value).send("ok")
    }
}
```
