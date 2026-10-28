# Request

[한국어](../request.md) | [API index](README.md)

Request corresponds to Express's req. Use methods such as `req.param("id")` rather than properties such as req.params.id. Keep Request within its owned request lifetime. Saved callbacks may resume later; work using it after an early response needs a returned pending Deferred lease. Response completion alone does not guarantee such ownership. Examples are API fragments with application-defined DTOs and services.

## Request information

| Method | Returns | Express | Meaning |
|---|---|---|---|
| `method()` | Method | — | Retained nine-value enum; extended tokens throw IllegalStateException |
| `path()` | String | req.path | Current raw path without query; matching mount prefixes are removed |
| `rawPath()` | String | Encoded req.path | Original raw path without query; never trimmed by mounts |
| `remoteAddress()` | String | req.ip | Peer address |
| `isSecure()` | boolean | req.secure | Whether transport uses TLS |

By default, proxy headers are ignored. With app.trustProxy(true), remoteAddress uses the first X-Forwarded-For header's leftmost trimmed entry; isSecure uses the first X-Forwarded-Proto entry, with https meaning true. Missing/blank entries retain transport values. Values are not validated; enable this only behind a proxy that overwrites these headers. Forwarded is never read. This corresponds to Express's trust proxy setting. See [server options](app.md#server-options).

## Path parameters, query and headers

| Method | Returns | When missing | Express |
|---|---|---|---|
| `param(name)` | String | IllegalArgumentException, including an absent optional capture | req.params.name |
| `query(name)` | String | null | req.query.name |
| `queries(name)` | List<String> | Empty list | Query array |
| `header(name)` | String | null | req.get(name) |
| `headers(name)` | List<String> | Empty list | — |

Participating path captures are decoded exactly once; the pathname remains raw. Query values use their query decoder; headers are not URL-decoded. query/header return the first repeated value, queries/headers return every value as an unmodifiable list. Header names are case-insensitive. Use queryInt/paramInt or validate before converting a number.

```java
// GET /users/42/posts?page=2&tag=a&tag=b
app.get("/users/:id/posts", (req, res) -> {
    String id = req.param("id");                    // "42"
    String pageText = req.query("page");            // "2"
    int page = pageText == null ? 1 : Integer.parseInt(pageText);
    List<String> tags = req.queries("tag");         // ["a", "b"]
    String token = req.header("authorization");     // case-insensitive
    res.json(postService.find(id, page, tags));
});
```

An uncaught NumberFormatException from Integer.parseInt becomes 500. Treat invalid user input as 400 explicitly or use the convenience methods below.

## Input validation helpers

| Call | Behavior |
|---|---|
| `requireQuery("name")` | 400 if missing, empty or whitespace-only |
| `requireHeader("Authorization")` | 400 if missing, empty or whitespace-only |
| `requireForm("name")` | 400 if missing, empty or whitespace-only |
| `requireFile("file")` | 400 for missing or zero-byte upload |
| `queryInt("page", 1)` | Default 1 only if absent; malformed/overflowing int gives 400 |
| `formInt("count", 1)` | Default only if absent; invalid int gives 400 |
| `paramInt("id")` | Integer path value; invalid int gives 400 |

Empty strings, decimal fractions and surrounding whitespace are not missing numbers and produce errors. Negative integers are valid; enforce business ranges yourself. An undeclared path parameter remains a programming error. For an optional missing capture, use paramOrNull before choosing a default; paramInt does not supply one. Required-string helpers return the original value after checking it.

## Body

### JSON

| Method | Purpose |
|---|---|
| `body(Class<T> type)` | Decode to a concrete class |
| `body(TypeRef<T> type)` | Preserve a generic type such as List<User> |

```java
User user = req.body(User.class);
List<User> users = req.body(new TypeRef<List<User>>() {});
```

These two lines are alternatives for different body types. Running both on one request makes the second throw BodyAlreadyConsumedException.

- Accept application/json or application/<name>+json, such as application/merge-patch+json.
- Read UTF-8 only. A charset parameter must equal utf-8, case-insensitively; other charsets produce 415 as in Express body-parser. Invalid UTF-8 produces 400.
- Default Gson accepts one leading UTF-8 BOM (EF BB BF); repeated BOMs produce 400. A custom JsonCodec defines its own BOM handling.
- JSON null returns @Nullable null; an empty body is 400 rather than null.
- There is no automatically populated untyped map as in Express req.body. Use req.body(Map.class); default Gson reads numbers as Double (42 becomes 42.0) and nested objects as Map.
- There are no jsonTree/jsonMap methods because they would expose library-specific tree types or require another tree model.
- See [JSON](json.md) for codec configuration.
- Body/cookie accessors and Response.json/addCookie are interface default methods. Test fakes that do not override them throw UnsupportedOperationException; this preserves compatibility with older implementations.

| Situation | Outcome |
|---|---|
| Non-JSON Content-Type or non-UTF-8 charset | 415 |
| Malformed JSON, trailing data, empty body or invalid UTF-8 | 400 BAD_REQUEST |
| Body above app.jsonLimit, default 1 MiB | 413 |
| Nesting above the default depth 64 | 400 |
| Different type or mixed JSON/form access | BodyAlreadyConsumedException |

### Forms and files

| Method | Returns | When absent | Meaning |
|---|---|---|---|
| `form(name)` | String | null | Text in urlencoded or multipart form |
| `file(name)` | UploadFile | null | First multipart file |
| `files(name)` | List<UploadFile> | Empty list | All files with the name |
| `parts()` | List<UploadFile> | Empty list | Every multipart part in body order, including text with null filename |

Text parts use UploadFile too, giving every part one type while preserving order. `formLimit(bytes)` limits the whole urlencoded body and aggregate multipart text contents, default 1 MiB, inclusive range 1..1073741824 bytes. Requests above it receive 413. The name mirrors jsonLimit and Express's express.urlencoded({ limit }).

```java
app.formLimit(2L * 1024 * 1024);   // 2 MiB

app.post("/profile", (req, res) -> {
    String name = req.form("name");                  // text field
    UploadFile avatar = req.file("avatar");          // one file, or null if absent
    List<UploadFile> photos = req.files("photos");   // all files with this name
    int fields = 0;
    for (UploadFile part : req.parts()) {            // includes text fields
        if (part.filename() == null) {
            fields++;
        }
    }
    res.json(Collections.singletonMap("photos", photos.size()));
});
```

Kotlin:

```kotlin
app.formLimit(2L * 1024 * 1024)

app.post("/profile") { req, res ->
    val name: String? = req.form("name")
    val avatar: UploadFile? = req.file("avatar")
    val photos: List<UploadFile> = req.files("photos")
    val fields = req.parts().count { it.filename() == null }
    res.json(mapOf("photos" to photos.size))
}
```

See [uploads](multipart.md).

### Reuse JSON with the same type

The stream is read once. A successful body result, including null, is cached for an equal type. A different type, a JSON/form mix, or retry after parsing failure throws BodyAlreadyConsumedException. The form/file/files/parts accessors share one form parse.

```java
User user = req.body(User.class);   // read once
validate(user);                     // pass the value to other functions
service.save(user);
```

Size enforcement begins before parsing. See [defaults and security](defaults.md).

## Cookie

| Method | Returns | When absent |
|---|---|---|
| `cookie(name)` | Cookie | null |
| `getCookies()` | Cookie[] | Empty array |

```java
Cookie session = req.cookie("session");
if (session != null) {
    String token = session.getValue();
}
```

Browsers send only cookie names and values. HttpOnly, Secure, SameSite and other attributes on request cookies do not describe how the browser stored them. See [cookies](cookie.md).

## Request attributes

Use attributes to pass middleware data, instead of Express's req.user or res.locals.user.

| Method | Purpose |
|---|---|
| `attribute(key, value)` | Store; null removes |
| `attributeAs(key, Class<T>)` | Typed read; null if missing |
| `removeAttribute(key)` | Explicit removal; absent key is harmless |

```java
app.use((req, res, next) -> {
    req.attribute("user", auth.authenticate(req.header("Authorization")));
    next.run();
});

app.get("/me", (req, res) -> {
    User user = req.attributeAs("user", User.class);
    res.json(user);
});
```

Attributes disappear with the request and are not shared with other requests. An incompatible stored type throws ClassCastException. Primitive class tokens such as int.class and Kotlin Int::class.java read boxed Integer values. Literal null removes without a cast.

```java
Object none = null;
req.attribute("user", none);                       // remove the user attribute
String left = req.attributeAs("user", String.class); // null
```

Kotlin:

```kotlin
req.attribute("user", null)                // remove the user attribute
val left = req.attributeAs("user", String::class.java)
```

## Services and app-wide values

| Method | Returns | Meaning |
|---|---|---|
| `service(Class<T>)` | T | Service registered in onStart; missing service throws ServiceNotFoundException |
| `application()` | ApplicationContext | Shared attributes and service registry |

```java
app.get("/users", (req, res) -> {
    UserService users = req.service(UserService.class);
    res.json(users.findAll());
});

String version = req.application().attributeAs("appVersion", String.class);
```

Every request uses the same service objects. Make shared service state thread-safe. See [lifecycle and services](lifecycle.md).

## SSE

`lastEventId()` returns the Last-Event-ID header, or null if absent. EventSource sends it on reconnection. See [SSE](sse.md).

## Mounts and path views

| Method | Common HTTP meaning |
|---|---|
| `methodToken()` | Actual wire method, including COPY/PROPFIND; use this rather than `method()` for extended tokens |
| `rawPath()` | Original raw pathname without query, unchanged across mounts |
| `path()` | Current raw pathname after consumed mount prefixes |
| `url()` | Current raw path plus exact raw query; an empty query preserves trailing `?` |
| `originalUrl()` | Original raw path/query, unchanged across mounts |
| `baseUrl()` | Accumulated actual raw matched mount prefixes; empty at top level |

For `/api/users/a%2Fb?x=%2B` inside a `/api` mount, path is `/users/a%2Fb`, url is `/users/a%2Fb?x=%2B`, baseUrl is `/api`, and originalUrl is `/api/users/a%2Fb?x=%2B`. A matching `:id` is `a/b`. The request's original rawPath is `/api/users/a%2Fb`. Prefix middleware also sees trimmed views during its callback; exiting a child restores parent views. Body cache, attributes, cookies and services belong to the same request identity throughout. Views after same-router continuation reflect the actual cursor rather than a blanket rollback.

Only matched captures are decoded: literal `%ZZ` can match a literal route, while capturing `%ZZ` is 400. A path capture keeps `+`; query decoding treats `+` as space. Encoded slash can occur in a common HTTP capture. Static filesystem containment, WS upgrade path restrictions and transport Host/framing validation remain separate strict boundaries.

## Parameter shape and preprocessing

| Method | Result |
|---|---|
| `param(name)` | First scalar/list value; absent name throws IllegalArgumentException |
| `paramOrNull(name)` | First value, null for absence |
| `paramValues(name)` | Immutable scalar singleton or wildcard segment list; empty for absence |
| `pathParams()` | Immutable map: String scalars and immutable List<String> wildcards; omitted optionals absent |
| `param(name, value)` | Replace decoded scalar; null removes binding |
| `paramValues(name, values)` | Replace with immutable snapshot of decoded segments |

An optional `:id` missing from the actual match is absent even if declared in the pattern. `/files/*parts` capturing `a%2Fb/c` yields `["a/b", "c"]`, split before decoding. A one-segment wildcard remains a list in pathParams; do not infer its kind from length. Last participating duplicate binding controls shape. Request-level scalar convenience does not replace collection observation.

`Router.create(true)` merges parent parameters; child values win and numeric keys follow the routing merge rules. Param hooks run only for present local captures. Use setters in hooks to preprocess downstream observations; hooks receive original decoded capture values, while cached results preserve modifications and control flow. See [routing](routing.md#independent-router-mounts-and-param-hooks).

## Deferred ownership

An unfinished callback may return and resume later. For work that still needs body/input/uploads after responding, return pending Deferred from an AsyncRouteHandler. Settle after last use; resolving does not end/continue a response. Multiple returned pending results hold independent leases. Abort, timeout or shutdown cancellation may revoke them. Built-in exchange retention keeps unread input/context while owned; this is not eager body parsing and a custom adapter's compatibility no-op does not provide physical retention. Never retain Request for unrelated work after final cleanup. See [Deferred](middleware.md#deferred-and-late-work) and [lifecycle](lifecycle.md).

## Optional wildcard example

/files has no key and responds with an empty list. /files/a%2Fb/c responds with the immutable list ["a/b", "c"]. bindings is the immutable scalar/list observation map.

```java
app.get("/files{/*parts}", (request, response) -> {
    Map<String, Object> bindings = request.pathParams();
    List<String> parts = request.paramValues("parts");
    String optional = request.paramOrNull("parts");
    response.json(parts);
});
```
