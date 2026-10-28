# Static files, MIME and caching

[한국어](../static.md) | [API index](README.md)

> **Version scope:** This page describes the 0.10.0 target checkout, not a Maven publication announcement.

## staticFiles

This serves the same purpose as Express `app.use(express.static('public'))`; NaruServe retains the filesystem safety and method policies below:

```java
app.staticFiles("/", new File("./www"));
```

Examples configure an existing app. Paths below assume a www directory containing the named files.

| Request | File |
|---|---|
| / | www/index.html |
| /assets/app.js | www/assets/app.js |
| /docs/ | www/docs/index.html |

Directory requests without the slash receive 301 to the slash form, preserving the encoded path and query: /docs?x=1 becomes /docs/?x=1. Empty segments such as //assets yield 404, preventing a Location beginning // from redirecting to another host. Mounts are checked in registration order, falling through if a file is absent.

### Routes and methods

- In the 0.10.0 common HTTP stack, `staticFiles` adds a service **at its registration position**. An earlier route or middleware that answers wins; a successful earlier static service stops later routes/middleware. Put authentication before the static registration. There is no universal “all routes, then static” pass.
- A miss continues to the next registered layer with the request view intact. A different-method route does not reserve the ordinary HTTP path: `GET`/`HEAD` can reach a later static service, and an unanswered method/path falls through to common HTML `404`, not that route's global `405`.
- A real retained WebSocket-only footprint remains reserved regardless of static registration order. A file or SPA fallback cannot replace its `426`/legacy OPTIONS/method behavior. A common HTTP route sharing that pathname still follows common stack order. See [WebSocket](websocket.md).
- A reached static service answers real files, directory indexes/listings and slash redirects for `GET`/`HEAD`. For another token, including `OPTIONS`, `TRACE`, `CONNECT` or `COPY`, a real filesystem resource yields `405` with `Allow: GET, HEAD`; an absent resource continues to later layers. Common HTTP has no earlier unknown-method/TRACE/CONNECT `501` guard. The separate WS/strict profile retains its guards.
- At a matching static service, invalid traversal, encoded slash/backslash, NUL and double-encoded paths yield `400` before static method handling. This protects filesystem access and does not impose global validation on earlier common routes. Backslashes are rejected on every platform because Windows treats them as separators.
- SPA content is not a real writable resource. A non-`GET`/`HEAD` miss therefore continues and eventually gets `404` if no later layer answers. Rejected symbolic-link directories receive no slash redirect.
- `app.staticFiles("/static", new File("./public"))` maps `/static/a.css` to `public/a.css`. This filesystem mount prefix is removed for lookup/cache matching; it is distinct from a common `Router` mount. A static miss does not leave later layers with the prefix stripped.

Static safety/method rejections retain their configured error renderer (standard JSON by default). A thrown file-open/read failure enters only later ordered error layers; stale file-only headers are cleared when falling back to an error. A failure after commit cannot send a second response. Unhandled common HTTP errors and ordinary no-route fallback use [finalhandler HTML](defaults.md#default-error-body-and-exposed-details).

### Options

```java
app.staticFiles("/", new File("./www"), options -> {
    options.index("index.html");
    options.directoryListing(false);
    options.spaFallback(true);
    options.cache("**/*.html", CachePolicy.noCache());
    options.cache("/assets/**", CachePolicy.publicFor(365, TimeUnit.DAYS).immutable());
    options.mime(".data", "application/x-data");
    options.spaExclude("/admin");
    options.allowSymlinks(false);
});
```

| Option | Default | Behavior |
|---|---|---|
| index(name) | index.html | Directory index file |
| directoryListing(boolean) | false | Show directory contents; symbolic links are omitted even if allowed for serving |
| spaFallback(boolean) | false | Serve index for missing HTML-navigation paths |
| spaExclude(prefixes...) | None added | Add exclusions; /api and /ws are always excluded |
| cache(pattern, policy) | None | Match mount-relative paths starting /; patterns without / act as if prefixed **/; first matching policy wins |
| mime(ext, type) | None | Mount-local type; extension may have a leading dot |
| allowSymlinks(boolean) | false | Allow links below the root only if their canonical targets remain inside it |

Options are copied and frozen per mount at listen. Later changes to a retained options object do not change it. Cache globs: * stays within one segment; ** matches zero or more segments, so **/*.html includes /index.html. On /static, /assets/** matches /static/assets/x.js and *.css matches /static/a/b.css. First-match ordering reads top-to-bottom like Express middleware.

### SPA fallback

For React/Vue-style client routing, a reached static service can serve `index.html` for a missing `GET`/`HEAD` path such as `/dashboard/settings` when `Accept` contains `text/html` (not merely `*/*`). An earlier answering HTTP layer stops the service. A **later** explicit route does not automatically outrank an earlier SPA service; register API routes first or add exclusions. Retained WS-only footprints are always reserved.

The `/api` and `/ws` prefixes, including the prefix itself, are always excluded, preventing accidental API/WebSocket requests from receiving an SPA document. `spaExclude` adds exclusions without replacing those defaults. Prefixes match segment boundaries: `/apix` is not excluded. With several mounts, real file lookup takes precedence over SPA content across mounts; when none has a file, the first reached matching `spaFallback(true)` service can send its index.

### Blocked resources

- Traversal, encoded dot-dot/slash/backslash, double encoding or NUL used to escape the root.
- Symbolic links by default, even those pointing inside. allowSymlinks(true) still requires canonical targets within the mounted root.
- Dotfiles such as .git and .env.
- Directory listings unless enabled.

Rejected files look absent with 404. The java.io resolver cannot tie its post-open recheck to an open file descriptor, so replacing path components between recheck and read remains a TOCTOU limitation. Java 8 can cache canonical paths for up to 30 seconds; use -Dsun.io.useCanonCaches=false to observe changed links immediately. Android has no such cache.

Static responses include X-Content-Type-Options: nosniff. Mount a directory containing only public files, keeping databases, keys and configuration elsewhere.

## MIME

### Priority

1. Mount-local options.mime.
2. App-wide app.mimeTypes().register.
3. Built-in types.
4. application/octet-stream.

```java
app.mimeTypes()
   .register(".mydata", "application/x-mydata")
   .register(".custom", "text/plain");
```

Empty MIME values or invalid header characters such as CR/LF throw IllegalArgumentException at register/options.mime time, preventing repeated request-time 500 errors.

### Built-in table

| Extension | MIME |
|---|---|
| .html, .htm | text/html |
| .css | text/css |
| .js, .mjs | text/javascript |
| .json, .map | application/json |
| .webmanifest | application/manifest+json |
| .wasm | application/wasm |
| .svg | image/svg+xml |
| .png | image/png |
| .jpg, .jpeg | image/jpeg |
| .webp | image/webp |
| .avif | image/avif |
| .woff | font/woff |
| .woff2 | font/woff2 |
| .mp4 | video/mp4 |
| .webm | video/webm |
| .pdf | application/pdf |
| .txt | text/plain |
| .csv | text/csv |
| .xml | application/xml |
| .gif | image/gif |
| .ico | image/x-icon |
| .mp3 | audio/mpeg |
| .ogg | audio/ogg |
| Other | application/octet-stream |

Text types include charset=UTF-8.

## Cache policy

CachePolicy creates Cache-Control values for res.cache and static options.cache.

```java
Map<String, Object> privateData = Collections.singletonMap("balance", 1000);
Map<String, Object> publicData = Collections.singletonMap("version", "1.0");
res.cache(CachePolicy.noStore()).json(privateData);
res.cache(CachePolicy.publicFor(1, TimeUnit.HOURS)).json(publicData);
```

Kotlin:

```kotlin
val policy = CachePolicy.publicFor(365, TimeUnit.DAYS).immutable()
println(policy.headerValue)   // public, max-age=31536000, immutable
res.cache(CachePolicy.noCache()).json(mapOf("version" to "1.0"))
```

### Default server policy

app.defaultCache(policy) applies only to responses without Cache-Control. Default null adds nothing, avoiding an implicit cache-behavior change. At commit, any existing response cache/header value, matched mount pattern or framework cache policy remains unchanged.

```java
app.defaultCache(CachePolicy.noStore());   // no-store for responses without an explicit policy
```

| Factory | Header | Use |
|---|---|---|
| noStore() | no-store | Authenticated APIs, personal data |
| noCache() | no-cache | HTML revalidated on each use |
| privateFor(10, MINUTES) | private, max-age=600 | User-specific data |
| publicFor(1, HOURS) | public, max-age=3600 | Same response for everyone |
| immutable() | Append immutable | Content-hashed JS/CSS filenames |

Use noStore for authenticated REST, noCache plus automatic ETag for HTML, publicFor(365, DAYS).immutable for app.3f9a2c.js-style assets, and privateFor or noStore for private downloads. Never use publicFor for user-specific responses: a shared proxy/CDN could serve them to another user.

## Range and conditional requests

staticFiles, res.file and res.download support resumable downloads and media seeking:

| Request | Response |
|---|---|
| Matching If-None-Match | 304 without body; precedes If-Modified-Since |
| Not modified since If-Modified-Since | 304 |
| One valid Range, bytes=0-1023 | 206 with Content-Range |
| End exceeds size | Clamp end and return 206 |
| Start at/beyond size | 416 with Content-Range: bytes */size |
| Multiple ranges, bytes=0-1,5-9 | Ignore Range and send full 200 |
| If-Range differs | Full 200 |

Responses include Accept-Ranges: bytes, ETag and Last-Modified. Weak ETags (W/...) do not satisfy If-Range. Files stream without loading the complete contents into memory.
