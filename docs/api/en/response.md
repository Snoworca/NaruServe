# Response

[한국어](../response.md) | [API index](README.md)

Response corresponds to Express's res. status, header, addCookie and cache return Response for chaining. Examples use application-provided variables and services; see the [quick start](README.md#quick-start) for a complete server.

```java
res.status(201).header("X-Request-ID", id).json(created);
```

## Method summary

### Status and headers

| Method | Returns | Express | Behavior |
|---|---|---|---|
| `status(code)` | Response | res.status | Default 200; outside 100..999 throws IllegalArgumentException |
| `header(name, value)` | Response | res.set | Replace case-insensitive same-name headers; invalid token name, controls except tab, or characters above U+00FF throw IllegalArgumentException |
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
| `stream(in, contentType)` | Argument | stream.pipe(res) | Return immediately; copy on request thread after handler return; framework closes the input exactly once on success, failure or abort |
| `file(File)` | File extension | res.sendFile | Range/conditional requests; no Content-Disposition |
| `download(File, filename)` | File extension | res.download | Content-Disposition: attachment |
| `end()` | — | res.end | No body |
| `sse()` | text/event-stream | — | Return SseClient; see [SSE](sse.md) |
| `abort(error)` | — | res.destroy | Abort connection; harmless after completion/abort |

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
- Returning without a body ends with the selected status/headers. A thrown exception before commit enters error handling and standard JSON; after streaming starts, it aborts the connection.
- status accepts 100..999, matching Express 5's status range (its error is RangeError).
- 204, 304 and HEAD suppress bodies, including values passed to json.
- Select and finish the response before returning. SSE, file, download and stream transmission may continue after handler return.

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
- file/download reject a supplied path that is itself a symbolic link, missing, a directory or not a regular file with standard 404 JSON.
- file leaves Content-Disposition unset so the browser may display it; download requests an attachment and sanitizes filename header-injection characters.

### Default cache policy

app.defaultCache(policy) supplies Cache-Control only if the response has none. Default null adds nothing; existing response or framework cache headers remain unchanged. See [server policy](static.md#default-server-policy).

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

stream sends status and headers immediately and returns; subsequent status/header calls throw. Set them first. When called inside a handler, copying runs on that request thread after handler return. NaruServe closes the input. Do not wrap it in try-with-resources or Kotlin use, which would close it before copying begins.

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

## Status only

```java
res.sendStatus(204);   // empty body
res.sendStatus(404);   // Not Found
```

## Redirect

res.redirect(url) defaults to 302. res.redirect(status, url) selects the status.

```java
res.redirect("/login");
```
