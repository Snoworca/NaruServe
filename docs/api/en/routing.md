# Routing

[한국어](../routing.md) | [API index](README.md)

## Three registration styles

| Style | Use | Example |
|---|---|---|
| `app.get(path, handler)` | One method, one handler | `app.get("/users", list)` |
| `app.route(path).get(h1).delete(h2)` | Different handlers at one path | Below |
| `app.route(MethodSet, path, handler)` | Branch on several methods in one handler | Below |

Examples are API fragments: app, handlers and services must already exist. See the [quick start](README.md#quick-start) for a standalone program.

### Per-method handlers

This is equivalent to Express's app.route:

```java
app.route("/users/:id")
   .get(getUser)
   .put(updateUser)
   .delete(deleteUser);
```

It equals separate app.get, app.put and app.delete registrations. RouteBuilder supports get, post, put, patch, delete, head, options and use(middleware), returning itself. `route("/admin").use(auth).use(adminOnly).get(h)` runs auth, adminOnly, h. use affects only methods registered after it. Java varargs must come last, which is why multiple middleware use a builder rather than `get(path, Middleware..., RouteHandler)`. See [middleware registration](middleware.md#registration).

### Several methods, one handler

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

Kotlin:

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

Method is an enum:

```java
public enum Method { GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE, CONNECT }
```

MethodSet is immutable: combination methods return a new set value.

| Expression | Members |
|---|---|
| `Method.get()` | GET |
| `Method.get().post()` | GET, POST |
| `Method.get().delete().post()` | GET, DELETE, POST |
| `Method.of(Method.PUT, Method.PATCH)` | PUT, PATCH |
| `Method.any()` | GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS; never TRACE/CONNECT |
| `Method.any().except(Method.DELETE)` | All ordinary methods except DELETE |

- get, any and of are static Method functions; there is no `Method.GET.delete()`.
- MethodSet provides get, head, post, put, patch, delete, options and except(Method). with(Method) adds arbitrary enum members, including TRACE/CONNECT.
- Adding an existing member or removing an absent member preserves the set value. Null arrays/elements in Method.of throw NullPointerException.
- TRACE/CONNECT are disabled by default and absent from any even when policy enables them. Enable them with [router policy](#router-policy) and register explicitly.
- Express app.all corresponds to `app.route(Method.any(), path, h)` with that exclusion.
- For one method, pass the enum directly: `app.route(Method.GET, "/health", h)`.

### Inspect a MethodSet

| Method | Result |
|---|---|
| `contains(Method)` | Membership |
| `iterator()` | Enum declaration order; remove throws UnsupportedOperationException |
| `size()` | Member count |

MethodSet implements Iterable. Equal members mean equal sets and hash codes, irrespective of construction order. toString joins members in declaration order with comma-space, for example `GET, POST`, matching an Allow header. Express has no equivalent method-set type.

```java
MethodSet set = Method.of(Method.DELETE, Method.GET, Method.POST);
boolean canPost = set.contains(Method.POST);   // true
for (Method m : set) {                         // GET, POST, DELETE order
    System.out.println(m);
}
int n = set.size();                            // 3
```

Kotlin uses contains through `in` and iterator through a for loop:

```kotlin
val set = Method.get().post().except(Method.POST)
val canGet = Method.GET in set                 // true
for (m in Method.of(Method.DELETE, Method.GET)) {  // GET, DELETE order
    println(m)
}
val n = set.size()                             // 1
```

## Path patterns

| Pattern | Matches | Read |
|---|---|---|
| `/users` | `/users` | — |
| `/users/:id` | `/users/42`, `/users/abc` | `req.param("id")` |
| `/users/:userId/posts/:postId` | `/users/1/posts/9` | userId and postId parameters |

- A parameter occupies one complete segment and never crosses `/`.
- Names match `[A-Za-z_][A-Za-z0-9_]*`. `/:id.json`, `/:a-b` and `/:from-:to` fail at registration. Unlike Express 4, parameters cannot have a fixed suffix; accept `/files/:name` and split in the handler.
- Write fixed segments as decoded characters, for example `/café`. Percent-encoded patterns, literal spaces, controls, # and dot/dot-dot segments fail at registration because they cannot match validated requests. Express compares encoded patterns such as `/a%20b` differently; revise them when migrating.
- Query strings do not participate; use req.query.
- Express ?, *, +, parentheses and regular-expression patterns are unsupported.

### Matching

Matching distinguishes case and trailing slashes by default. `/Users` differs from `/users`; `/users/` differs from `/users`. Policy can change slash merging, trailing slashes and case. Requests decode exactly once. Invalid escapes, NUL and traversal are rejected; TinyHttpd can reject these before the app with a short plain-text 400 that bypasses onError and standard JSON. See [defaults](defaults.md).

### Priority

Fixed segments outrank parameters regardless of registration order:

```java
app.get("/users/:id", getUser);
app.get("/users/me", getMe);     // /users/me always selects this handler
```

listen rejects duplicate method/path registrations and equally ranked patterns such as `/a/:x` and `/a/:y` for one method.

## Automatic responses

| Situation | Response |
|---|---|
| No path | 404 |
| Path exists, method missing | 405 with Allow |
| HEAD on GET route | Run GET handler, suppress body |
| OPTIONS without a handler on a known path | 204 with Allow |
| Unknown/disabled method | 501 |
| STOPPING | 503, Connection: close, standard SERVICE_UNAVAILABLE JSON |
| Full transport queue | Plain-text 503 from TinyHttpd; no routing or middleware |

Explicit head/options handlers take priority. Automatic HEAD keeps req.method() equal to HEAD, so branch on GET and HEAD together. App-generated 404/405 use the [standard error format](middleware.md#standard-error-format).

## Static files and WebSocket

Lookups proceed through explicit routes, static mounts and finally 404. A method mismatch can still fall through to static GET/HEAD handling as described in [static files](static.md). A path may have both an ordinary GET route and app.ws. Upgrade GET selects WebSocket where available. Without Upgrade, a WebSocket-only path returns 426, except automatic OPTIONS. App middleware runs before these decisions. See [lookup outcomes](#lookup-outcomes-and-order) and [middleware](middleware.md).

## Router policy

`RouterPolicy.builder()` controls path normalization and TRACE/CONNECT. It corresponds to Express strict-routing/case-sensitive-routing options, with stricter defaults.

| Builder method | Enabled behavior | Express equivalent |
|---|---|---|
| `mergeSlashes(boolean)` | `/a//b` becomes `/a/b` | None |
| `ignoreTrailingSlash(boolean)` | Ignore one trailing slash; preserve root | Strict routing off |
| `caseInsensitive(boolean)` | Compare fixed segments without case | Case-sensitive routing off |
| `enableTrace(boolean)` | Permit explicit TRACE registration/routing | None |
| `enableConnect(boolean)` | Permit explicit CONNECT registration/routing | None |

All switches default false; RouterPolicy.strict() is the default. Configure only before listen in NEW state: later calls throw IllegalStateException and null throws NullPointerException. Later policy calls replace earlier ones. Policy applies to every route at freeze regardless of when a route was registered. RouterPolicy is immutable; build copies the current builder values.

```java
app.routerPolicy(RouterPolicy.builder()
    .mergeSlashes(true)
    .ignoreTrailingSlash(true)
    .caseInsensitive(false)
    .enableTrace(false)
    .enableConnect(false)
    .build());
```

Kotlin:

```kotlin
app.routerPolicy(
    RouterPolicy.builder()
        .ignoreTrailingSlash(true)
        .caseInsensitive(true)
        .build()
)
```

## Routing contract

### Path-shape policy

Each switch is independent and fixed when the route table is built.

| Switch | On | Off |
|---|---|---|
| Merge slashes | `/a//b` matches `/a/b` | It matches neither `/a/b` nor `/a/:x/b` |
| Ignore trailing slash | `/users` and `/users/` match each other; root stays `/`; `/users//` also needs slash merging | Exact trailing slash |
| Ignore case | Locale-independent fixed segments; `/USERS/Bob` matches `/users/:name`, retaining Bob | `/Users` differs from `/users` |

Parameters retain their original once-decoded values under every policy.

### TRACE and CONNECT

Both yield unsupported-method outcomes by default at every path. Enable each separately and register by enum, such as `Method.of(Method.TRACE)`; Method.any never includes them. Registering a disabled method fails when the table freezes.

### Lookup outcomes and order

Lookup takes a method token, raw path and upgrade flag and returns the first applicable outcome:

| Order | Outcome | Condition |
|---|---|---|
| 1 | Unsupported method | Token does not exactly equal an enum name, or TRACE/CONNECT is disabled |
| 2 | No path | No pattern of any kind matches, including OPTIONS requests |
| 3 | Match | A route matches the request method |
| 4 | Automatic OPTIONS | OPTIONS with no explicit OPTIONS route |
| 5 | Automatic HEAD | HEAD with no HEAD route but a matching HTTP GET; not a WebSocket route |
| 6 | WebSocket only | Non-upgrade GET/HEAD on a WebSocket path without HTTP GET, or only WebSocket routes match |
| 7 | Method mismatch | Path exists but the method does not |

The dispatcher maps WebSocket-only to 426 with Upgrade: websocket and Connection: Upgrade. Upgrade GET first checks WebSocket routes. If none match, it falls back to normal GET lookup, including method mismatch or no-path results. Without the upgrade flag, WebSocket patterns never directly match an HTTP request.

### Allow set

Automatic OPTIONS and method mismatch share an Allow set: union the methods of every matching pattern, count WebSocket as GET, add HEAD when GET exists, and always add OPTIONS. `Method.any().except(DELETE)` yields `GET, HEAD, POST, PUT, PATCH, OPTIONS`. A POST-only path yields `POST, OPTIONS`, without HEAD.

### Per-method priority

Compare only routes of the request method, left to right; at the first difference, fixed segments beat parameters. GET `/a/b/c` selects `/a/b/:y` over `/a/:x/c`. DELETE `/users/me` selects DELETE `/users/:id` even if a more specific GET `/users/me` exists.

### Pattern validation and conflicts

Registration rejects unsupported Express syntax, empty or repeated parameter names, empty segments, invalid names, percent-encoded fixed segments, spaces, fragments and dot/dot-dot segments. Root and one trailing slash are allowed. Conflicts are checked together at listen before sockets open: equal patterns or parameter-name-only differences conflict for the same route kind and method. Error messages name both registrations.

Conflicts use policy-normalized patterns. Case-insensitive `/Users` and `/users`, or trailing-slash-insensitive `/a` and `/a/`, conflict; under strict defaults they do not. HTTP GET and WebSocket at the same path do not conflict.

### Method tokens and upgrades

Method tokens are case-sensitive: get, Get, FOO and PROPFIND are unsupported everywhere. The upgrade flag requires GET, a Connection header containing upgrade and an Upgrade header containing websocket. Both headers use comma-separated, case-insensitive tokens; `Connection: keep-alive, Upgrade` and `Upgrade: WebSocket` qualify, h2c and POST do not.

### Rejected paths and decoding

Before matching, validate the raw path, ignoring its query string from the first ?. Reject:

- Invalid escapes (`/a%2`, `/a%zz`) and non-UTF-8 bytes (`/a%C3`, `/a%FF`).
- Encoded slash `%2F` and literal/encoded NUL `%00`.
- Literal/encoded dot or dot-dot segments (`/./a`, `/a/..`, `/a/%2e`, `/a/.%2e/b`); `.hidden` remains valid to the router.
- Literal spaces, controls U+0001..U+001F/U+007F, # and non-ASCII raw characters.

Then percent-decode segments exactly once as UTF-8. Plus stays plus. `/files/%252e` matched against `/files/:name` returns `%2e`; `/caf%C3%A9` matches fixed pattern `/café`.
