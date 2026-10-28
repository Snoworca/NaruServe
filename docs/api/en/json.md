# JSON

[한국어](../json.md) | [API index](README.md)

NaruServe's equivalents to express.json() and res.json() use the JsonCodec interface, with Gson 2.14.0 as the default implementation. Examples are API fragments; DTOs and variables belong to your application.

## Read and write

```java
User user = req.body(User.class);                          // request to object
List<User> users = req.body(new TypeRef<List<User>>() {});  // generic type
res.json(users);                                            // object to response
ws.sendJson(user);                                          // WebSocket
```

The two request body calls above illustrate alternatives, not successive reads of different types on one request.

- req.body requires a JSON Content-Type. JSON null returns null; see [Request](request.md#json) for errors and limits.
- TypeRef preserves generic information such as List<User>; keep the anonymous-class braces `{}`.
- Kotlin `object : TypeRef<List<String>>() {}` records a covariance wildcard. With kotlin.Metadata retained, TypeRef normalizes extends wildcards to match Java's List<String>. If R8 removes the annotation, the wildcard remains, but default Gson decodes both equally.
- res.json writes objects, arrays, maps, strings, numbers and null as valid JSON.

These typed request readers and explicit `res.json` writes remain available in the 0.10.0 common HTTP checkout. A parse/limit failure enters downstream ordered error handlers; unhandled common HTTP errors are HTML with escaped Java exception details, not an automatic JSON envelope. Use error middleware and `res.status(...).json(...)` for an explicitly JSON API. Published coordinates below still name 0.9.0; they do not claim that 0.10.0 is already published.

A returned pending `Deferred` from an async route can retain the same cached body or unread input beyond response completion. Complete the result after the producer's last access, and never reuse cancelled input. See [request ownership](lifecycle.md#deferred-callbacks-and-request-resources).

## DTOs

The runnable [Java](../../../examples/java/src/main/java/example/JavaExample.java) and [Kotlin](../../../examples/kotlin/src/main/kotlin/example/KotlinExample.kt) examples use DTOs default Gson can construct and reject missing/null/blank names with 400. They do not enable Unsafe. For immutable Kotlin data classes, explicitly supply a codec that understands constructors and nullability.

```java
public class User {
    @SerializedName("id")   private String id;
    @SerializedName("name") private String name;

    public User() {}   // no-argument constructor
    // getter/setter
}
```

- Provide a no-argument constructor; default Gson disables constructor-bypassing Unsafe allocation.
- Android release builds rename fields. Use SerializedName annotations or keep rules to preserve JSON keys instead of a/b-style names.
- There is no automatic polymorphic binding from untrusted class names.
- Bind and validate a request DTO rather than exposing database/domain entities directly.

## Default Gson configuration

```java
Gson gson = new GsonBuilder()
    .setStrictness(Strictness.STRICT)
    .disableJdkUnsafe()
    .create();
app.jsonCodec(new GsonJsonCodec(gson));
```

GsonJsonCodec lives in NaruServe-gson, not the dependency-free public API module. NaruServe-gson exposes Gson as an api dependency, so constructing Gson does not need an additional dependency. The naruserve bootstrap exposes this module too; naruserve-kotlin and naruserve-android inherit it. Add this dependency only when assembling with the builder without the bootstrap:

```groovy
implementation 'com.snoworca.naruserve:naruserve-gson:0.9.0'
// transitive Gson dependency: com.google.code.gson:gson:2.14.0
```

To customize date formats or naming, build Gson and supply app.jsonCodec, keeping STRICT and disableJdkUnsafe. GsonJsonCodec raises an unspecified LEGACY_STRICT setting to STRICT to reject comments and unquoted names; deliberately LENIENT Gson remains lenient.

Output retains Gson's default HTML escaping (for example `<` becomes `\u003c`), reducing accidental interpretation as HTML tags when embedded. To disable it, explicitly provide Gson built with disableHtmlEscaping().

## Limits

| Limit | Default | Maximum | Exceeded | Set through |
|---|---|---|---|---|
| Request JSON bytes | 1 MiB | 1 GiB | 413 | app.jsonLimit(bytes) |
| Nesting depth | 64 | 255 | 400 | new GsonJsonCodec(gson, maxDepth) |

- jsonLimit is an app setting, like express.json({ limit }), with inclusive range 1..1073741824. Out-of-range values throw `IllegalArgumentException` with `jsonLimit must be in 1..1073741824 bytes: <n>`. Configure before listen.
- Depth belongs to the codec because different codecs count nesting differently. GsonJsonCodec(gson) still uses 64; the second constructor argument changes it.
- Trailing data (`{} {}`) and an empty body produce 400. Empty input differs from JSON null.
- A custom Gson JsonDeserializer rejecting input with JsonParseException produces 400; type-definition failures such as no usable constructor (JsonIOException) produce 500.

```java
app.jsonLimit(256 * 1024);   // 256 KiB
```

Kotlin:

```kotlin
app.jsonLimit(256L * 1024)
app.jsonCodec(GsonJsonCodec(GsonJsonCodec.defaultGson(), 32))
```

See [defaults and security](defaults.md).

## Replace the JSON library

Implement JsonCodec and pass it to app.jsonCodec. Public API signatures contain no Gson types:

```java
public interface JsonCodec {
    <T> @Nullable T decode(Reader reader, Type type) throws IOException;   // null for a JSON null body
    void encode(@Nullable Object value, Writer writer) throws IOException;
}
```

| Library | Role in this project | Check before use |
|---|---|---|
| Gson 2.14.0 | Default codec and API 21 compatibility-check baseline | Keep the default |
| Moshi | No bundled codec | Version-specific Android minimum API and Kotlin constructor handling |
| Jackson | No bundled codec | Version-specific JVM/Android requirements |
| kotlinx.serialization | No bundled codec | Kotlin plugin, serialization model and Android requirements |

Keep the pinned Gson 2.14.0 for this project's API 21 baseline. When changing the codec or library version, check that version's platform requirements and rerun project compatibility checks. NaruServe does not ship adapters for the alternatives listed above.

## Kotlin caveats

Gson does not enforce Kotlin non-null properties or understand default parameters in the Kotlin-aware sense:

```kotlin
data class User(val name: String, val role: String = "user")
// No no-argument constructor: default Gson (Unsafe disabled) cannot instantiate this class.

data class Profile(val name: String = "", val role: String = "user")
// Defaults for every parameter generate a no-argument constructor, so decoding works.
// However, {"name":null} still puts null into the non-null name property.
```

Use Moshi codegen or a kotlinx.serialization codec if appropriate, or nullable DTO fields with explicit validation. See [Kotlin](kotlin.md).
