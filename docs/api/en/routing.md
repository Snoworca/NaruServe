# Routing

[한국어](../routing.md) | [API index](README.md)

This reference describes the **common HTTP profile in the 0.10.0 checkout**. The retained `routing.Router` and WebSocket upgrade rules use a [separate strict profile](#legacy-router-and-websocket). Check the [installation guide](README.md) for artifact availability.

## Registering routes

| Call | Purpose |
|---|---|
| `app.get(path, handler)` | Respond with a two-argument `RouteHandler` |
| `app.get(path, middleware...)` | Run three-argument `Middleware` callbacks in order |
| `app.route(path)` | Create one Route at this position, then append methods/callbacks to its builder |
| `app.route(MethodSet, path, handler)` | Existing enum-method-set convenience |
| `app.method("COPY", path, callbacks...)` | Register a valid HTTP method outside the enum |

### Per-method handlers

```java
app.route("/users/:id")
   .get(getUser)
   .put(updateUser)
   .delete(deleteUser);
```

This builder shares **one Route identity and stack position**. Three independent `app.get`/`put`/`delete` registrations do not always have the same control flow. Calling `route(path)` establishes the position; appending callbacks later does not move it. `RouteBuilder.get`, `post`, `put`, `patch`, `delete`, `head`, `options`, `method(token, Middleware...)` and `use(Middleware)` return the same builder. `use` appends an all-method callback at its current position in that Route. It does not move ahead of a response handler registered earlier.

### Several methods in one handler

The application supplies `find`, `create` and `remove`. The example explicitly chooses 405 in its `default` branch; that is not the common HTTP framework default.

```java
app.route(Method.get().post().delete(), "/users/:id", (req, res) -> {
    switch (req.method()) {
        case GET:
        case HEAD:   res.json(find(req.param("id"))); break;
        case POST:   res.status(201).json(create(req)); break;
        case DELETE: remove(req.param("id")); res.status(204).end(); break;
        default:     res.status(405).end();
    }
});
```

```kotlin
app.route(Method.get().post().delete(), "/users/:id") { req, res ->
    when (req.method()) {
        Method.GET, Method.HEAD -> res.json(find(req.param("id")))
        Method.POST -> res.status(201).json(create(req))
        Method.DELETE -> { remove(req.param("id")); res.status(204).end() }
        else -> res.status(405).end()
    }
}
```

## Method and MethodSet

```java
public enum Method { GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE, CONNECT }
```

The existing nine `Method` constants remain. Read the actual string through `req.methodToken()`. Calling `req.method()` for an extended token such as `COPY` or `PROPFIND` throws `IllegalStateException`. `method(token, path, callbacks...)` validates token syntax and normalizes the registration name to uppercase. Invalid syntax throws `IllegalArgumentException`; null throws `NullPointerException`.

| Expression | Set |
|---|---|
| `Method.get().post()` | GET, POST |
| `Method.of(Method.PUT, Method.PATCH)` | PUT, PATCH |
| `Method.any()` | GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS |
| `Method.any().except(Method.DELETE)` | The preceding set except DELETE |
| `set.with(Method.TRACE)` | Explicitly add TRACE |

`Method.any()` does not mean every token accepted by Express `app.all`. It remains a finite-set convenience. Common HTTP TRACE/CONNECT registrations do not require the legacy WS policy switches. Transport request-target, Host and framing restrictions still apply separately.

### Inspecting MethodSet

Use `contains`, `size` and `iterator`. Iteration follows enum declaration order and does not support `remove()`. Equality/hash code use membership; `toString()` produces `GET, POST`. Composition returns immutable sets, and null arrays/elements are rejected.

```java
MethodSet set = Method.of(Method.DELETE, Method.GET, Method.POST);
boolean canPost = set.contains(Method.POST);   // true
for (Method m : set) {                         // GET, POST, DELETE order
    System.out.println(m);
}
int n = set.size();                            // 3
```

```kotlin
val set = Method.get().post().except(Method.POST)
val canGet = Method.GET in set                 // true
for (m in Method.of(Method.DELETE, Method.GET)) {  // GET, DELETE order
    println(m)
}
val n = set.size()                             // 1
```

## Path patterns

Strings match the original **raw pathname**, excluding query text. The whole path is not decoded first. Only matched captures receive one strict UTF-8 decoding pass.

| String pattern | Meaning |
|---|---|
| `/users/:id` | Required value that does not cross a slash |
| `/files/*parts` | One or more segments, a `List<String>` wildcard |
| `/files{/*parts}` | Also accepts `/files`; omitted wildcard keys are absent |
| `/users{/:id}` | Optional whole brace group |
| `/:file{.:ext}` | Literal delimiter and optional extension within one segment |
| `/:"user-id"` or `/*"file-parts"` | Quoted parameter names |

Names use the supported Unicode identifier grammar or quoted names. Escape reserved literals with `\`. Express 4 string forms such as `?`, `+` and parenthesized regex do not transfer unchanged. An unnamed `/*` is invalid; use `/*parts`. String-grammar errors throw `RoutingConfigException`, with positions counted in Unicode code points. When a name occurs repeatedly, the **last participating binding** determines both value and scalar/list shape. An omitted optional group does not erase an earlier binding.

A literal such as `/a%20b` matches the encoded raw path. `/caf%C3%A9` and `/café` are different literals. `/literal/%ZZ` can match literally, but `/:value` capturing `%ZZ` produces a decoding error 400. Wildcards split on **literal `/` before decoding**: `/files/a%2Fb/c` yields `["a/b", "c"]`. A path capture preserves `+` as `+`. These HTTP rules do not relax static traversal/symlink checks or WS path rejection.

### Matching rules

Common HTTP defaults to **case-insensitive matching and one optional trailing slash**; repeated slashes remain meaningful. `use(path, ...)` matches a prefix, with string segment boundaries so `/api` does not match `/apix`. A child `Router.create(mergeParams, caseSensitive, strict)` owns its options.

### Priority

**Registration order** governs execution. Fixed paths do not gain specificity priority. A callback that responds without continuing prevents later routes from running. Duplicate and overlapping common HTTP routes are valid; use `next.run()`/`next.route()` to reach later candidates.

```java
app.get("/users/:id", getUser);
app.get("/users/me", getMe);     // reached only if the earlier route continues
```

This tries `getUser` first. Unless it continues, `getMe` does not run.

## Java Pattern and path alternatives

An `Object path` accepts a string, `java.util.regex.Pattern`, `RegexPathPattern`, or nested `Object[]`/`List<?>` alternatives of these values. The first matching alternative wins from left to right. Every alternative and callback is validated before registration, so invalid nested elements/null do not partially publish a registration. Do not express alternatives as the string `"/a|/b"`.

Regex paths use **Java Pattern**. ECMAScript regex, flags and `lastIndex` are not emulated. Matching searches the original raw path with `Matcher.find()`; use explicit `^`/`$` for a full match. String case/strict settings do not override Pattern flags. For use/mount middleware, even a searched regex match must start at offset 0 and end at a segment boundary; a non-prefix match is skipped. Named groups use their Java names. Unnamed groups receive their own zero-based ordinal keys (`"0"`, `"1"`), without named groups consuming those ordinals. Nonparticipating groups are omitted; participating empty groups retain `""`.

`RegexPathPattern.compile(Pattern)` supports ordinary patterns directly. Some embedded-flag combinations erase initial COMMENTS/UNIX_LINES settings from `Pattern.flags()`, making capture-name metadata ambiguous. Registration then fails clearly; pass original source and flags to `com.snoworca.naruserve.routing.RegexPathPattern.compile(source, originalFlags)`. No reflection, JNI or JavaScript engine is required.

## Independent Router, mounts and param hooks

`com.snoworca.naruserve.Router.create()` creates an independent HTTP stack. Import it explicitly to distinguish the retained `com.snoworca.naruserve.routing.Router`. `create()` defaults to `mergeParams=false`, `caseSensitive=false`, `strict=false`; `create(true)` merges parent captures into the child. Child values win name conflicts; contiguous unnamed numeric blocks shift on merge. For parent keys0/1 and child keys0/1, child values become2/3. Numeric shifting counts contiguous keys from0; noncanonical keys such as01 remain ordinary names. Mounting the same Router at several positions keeps request mount contexts separate. Use a library-created Router; arbitrary custom implementations are not assumed to contain its internal registry.

Inside `app.use(prefix, router)`, `path()`/`url()` lose the actual matched prefix, and `baseUrl()` accumulates it. `originalUrl()`/`rawPath()` remain original. Leaving the child restores the parent views. `use(prefix, middleware)` also removes its prefix during that callback. All views share the same Request, body cache, attributes and services. See [request views](request.md#mounts-and-path-views).

`app.param(name, ParamHandler)` and `router.param` preprocess present captures owned by that router. The signature is `(req, res, next, List<String> originalValue, String name)`. Change later reads with `req.param(name, value)` or `req.paramValues(name, values)`. Hooks follow actual captured-key order: canonical integer-index keys from `0` through `4294967294` sort numerically; other keys follow capture enumeration. `01` and `4294967295` are not integer-index keys.

Caching is local to a request/router context. Equal decoded scalars reuse hook results; list/wildcard values use reference identity, so fresh capture lists may rerun hooks. Omitted optional captures and values merely inherited from the parent do not trigger child hooks. Route skipping, errors and router exit propagate. A hook can retain `next` and resume later.

## Automatic responses

| Common HTTP situation | Outcome |
|---|---|
| No callback handles the path/method before fallthrough | 404 HTML, `Cannot METHOD /raw/path` |
| HEAD | Use GET in the first encountered Route when it has no HEAD; suppress transmitted body |
| Automatic OPTIONS | Collect methods actually traversed in the current router;200 with `Allow` and matching plain-text body |
| Valid extended method | Dispatch its registered token, otherwise 404 |
| Callback returns without response or next | Remain pending |

HEAD does not skip an earlier GET Route to find a separately registered later HEAD Route. Even when GET runs, `methodToken()`/`method()` remains HEAD. Preserve the GET Content-Length but send no body bytes. Automatic OPTIONS is router-local: a responding child does not add parent methods. It sends `Content-Type: text/plain` without charset and `X-Content-Type-Options: nosniff`; it does not unconditionally add OPTIONS to Allow. This differs from CORS preflight 204.

## Static files and WebSocket

Common HTTP walks `use`, routes, static mounts and error layers in **one registration order**. A static miss continues. A static mount registered before authentication may respond first, so review ordering. See [middleware](middleware.md) and [static files](static.md).

## Router policy

Explicit `app.routerPolicy(RouterPolicy)` applies case, trailing-slash and repeated-slash options to common HTTP too. It applies even when configured after routes but before `listen`. Omitting the call uses common HTTP defaults, which differ from `RouterPolicy.strict()`.

| Option | Explicit policy meaning |
|---|---|
| `mergeSlashes(true)` | Merge repeated slashes for matching; preserve raw path and actual consumed mount-prefix views |
| `ignoreTrailingSlash(true)` | Accept string trailing slash |
| `caseInsensitive(true)` | Compare strings without case |
| `enableTrace` / `enableConnect` | Method gates for the retained strict router/WS profile |

```java
app.routerPolicy(RouterPolicy.builder()
    .mergeSlashes(true)
    .ignoreTrailingSlash(true)
    .caseInsensitive(false)
    .enableTrace(false)
    .enableConnect(false)
    .build());
```

```kotlin
app.routerPolicy(
    RouterPolicy.builder()
        .ignoreTrailingSlash(true)
        .caseInsensitive(true)
        .build()
)
```

`RouterPolicy` is immutable. Builder switches all default false; `strict()` has those strict values. Null policy is rejected, and configuration is allowed only in NEW. A Pattern keeps its own flags.

## Legacy router and WebSocket

Direct `routing.Router`/`RoutePattern` retains its segment grammar, fixed-path priority, conflict checks and strict policy. `app.ws` path, Origin and upgrade validation is not replaced by common HTTP string grammar. Existing valid global/prefix authentication middleware still runs before WS upgrade. Advanced HTTP Pattern/alternatives, independent Router and HTTP additions after listen do not imply equivalent WS behavior.

Ordinary GET/HEAD on a WS-only path yields 426; automatic OPTIONS is 204; existing disabled/unknown method gates yield 501. Invalid encoding, encoded slash/NUL and traversal in upgrades remain strictly rejected. Common HTTP captures and static files cannot bypass that boundary. See [WebSocket](websocket.md).

## Independent Router and alternatives example

This runs a local id hook for three path alternatives and uses the actual mounted baseUrl. X-Mode: skip leaves this Route. Patterns explicitly anchor the whole match.

```java
com.snoworca.naruserve.Router api = com.snoworca.naruserve.Router.create(true);
api.param("id", (request, response, next, values, name) -> {
    request.param(name, values.get(0).toUpperCase(java.util.Locale.ROOT));
    next.run();
});
Object paths = new Object[] {
    "/users/:id{/:tab}",
    java.util.regex.Pattern.compile("^/legacy/(?<id>[0-9]+)$"),
    com.snoworca.naruserve.routing.RegexPathPattern.compile("^/item/(?<id>[0-9]+)$", 0)
};
api.get(paths, (request, response, next) -> {
    if ("skip".equals(request.header("X-Mode"))) {
        next.route();
        return;
    }
    response.send(request.baseUrl() + ":" + request.param("id"));
});
app.use("/api", api);
```

## Low-level matcher inspection

`com.snoworca.naruserve.routing.HttpPathPattern.compile(String path, boolean end, boolean caseSensitive, boolean trailing)` explicitly configures full versus segment-bounded prefix matching, case, and optional trailing slash. Grammar/optional-expansion errors throw `RoutingConfigException`; more than 256 optional alternatives are rejected. `match(rawPath)` returns null or immutable Match with `matchedPath()`, raw `captures(): Map<String,List<String>>` and `wildcards(): Set<String>`. Scalars are singleton lists here; wildcards split before decoding. The wildcard set reflects the last participating binding, not merely declared keys. `keys()` returns grammar-order Key metadata (`name()`, `wildcard()`), including optional/repeated declarations. This reusable engine-free matcher does not percent-decode or change legacy routing. It is an inspection/compiler API, not another accepted `Object path` registration type.

`RegexPathPattern.keys()` returns declared capture-key strings. Its immutable Match exposes `matchedPath()`, `start()`, `end()` and raw singleton capture lists; missing groups are absent. Matching creates a fresh Matcher each time. Integration performs the later strict decode into Request views; calling the helper directly does not. Both matcher types are immutable and shareable between requests.
