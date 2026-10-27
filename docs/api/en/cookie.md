# Cookie

[한국어](../cookie.md) | [API index](README.md)

NaruServe has its own Cookie class, similar to javax.servlet.http.Cookie. Read cookies without cookie-parser. The examples are handler fragments with application-supplied token values.

## Convenience methods

```java
res.cookie("session", token);
res.cookie("session", token, c ->
    c.httpOnly(true).secure(true).sameSite(SameSite.LAX).path("/").maxAge(3600)
);
res.clearCookie("session");
res.clearCookie("session", c -> c.path("/app").domain("example.com"));
```

Convenience cookies default to Path=/. Delete using the same Path and Domain used to create the cookie. clearCookie forces Max-Age=0 after the callback, so a callback maxAge cannot undo deletion. maxAge uses seconds. Names, values and options use Cookie validation; invalid input or a throwing callback appends no Set-Cookie.

Kotlin supports trailing lambdas:

```kotlin
res.cookie("session", token) { c ->
    c.httpOnly(true).secure(true).sameSite(SameSite.LAX).path("/")
}
res.clearCookie("session") { c -> c.path("/") }
```

You can still construct Cookie explicitly. A directly created Cookie has no Path by default, unlike the convenience methods.

## Send

```java
Cookie cookie = new Cookie("session", token);
cookie.setHttpOnly(true);
cookie.setSecure(true);
cookie.setPath("/");
cookie.setMaxAge(3600);            // seconds
cookie.setSameSite(SameSite.LAX);
res.addCookie(cookie);
```

Express equivalent:

```javascript
res.cookie('session', token, { httpOnly: true, secure: true, path: '/', maxAge: 3600 * 1000, sameSite: 'lax' });
```

maxAge units differ: Express uses milliseconds; NaruServe uses seconds, matching Set-Cookie Max-Age.

Kotlin property syntax:

```kotlin
val cookie = Cookie("session", token).apply {
    isHttpOnly = true
    secure = true
    path = "/"
    maxAge = 3600
    sameSite = SameSite.LAX
}
res.addCookie(cookie)
```

## Attributes

| Getter / setter | Meaning |
|---|---|
| `getName()` | Constructor-only name |
| `getValue()` / `setValue(String)` | Value |
| `getPath()` / `setPath(String)` | Path |
| `getDomain()` / `setDomain(String)` | Domain |
| `getMaxAge()` / `setMaxAge(int)` | Seconds; default -1. Negative omits Max-Age (session cookie); zero deletes; positive persists for that duration |
| `getSecure()` / `setSecure(boolean)` | Secure |
| `isHttpOnly()` / `setHttpOnly(boolean)` | HttpOnly |
| `getSameSite()` / `setSameSite(SameSite)` | SameSite |

SameSite values are STRICT, LAX and NONE. Browsers require Secure for SameSite.NONE. v1 emits Max-Age rather than Expires, avoiding clock-skew dependence and relying on modern browser support. It has no signed-cookie secret management or req.signedCookies equivalent. Implement signing/verification, for example with HMAC, in middleware when needed.

Getter names follow Servlet Cookie: getSecure and isHttpOnly become Kotlin secure and isHttpOnly properties.

## Value encoding

NaruServe never encodes or decodes cookie values. Express res.cookie URL-encodes automatically; NaruServe sends the provided value. Values must contain RFC 6265 cookie-octets: ASCII excluding spaces, quotes, comma, semicolon, backslash and controls. Encode other characters yourself, for example with URLEncoder. Leaving the encoding to the application preserves interoperability with other servers.

```java
Cookie cookie = new Cookie("name", java.net.URLEncoder.encode("홍길동", "UTF-8"));
res.addCookie(cookie);
```

Non-token names or invalid value characters cause IllegalArgumentException from constructors, setters or addCookie.

## Read

```java
Cookie session = req.cookie("session");   // null if absent
Cookie[] all = req.getCookies();          // empty array if absent
```

Parsing is tolerant: skip pairs without `=` or with invalid token names. Trim whitespace around the name/value (`a = 1` gives a=1), as in Express's cookie parser; otherwise retain the value, including quotes and percent escapes.

Browsers send only names and values. Do not infer security/storage attributes from request Cookie.getSecure(), isHttpOnly(), getSameSite() or getPath().

## Delete

Send maxAge zero with the same Path and Domain as creation, corresponding to Express res.clearCookie:

```java
Cookie expired = new Cookie("session", "");
expired.setPath("/");
expired.setMaxAge(0);
res.addCookie(expired);
```

## Security

- Header-injection characters such as CR/LF are rejected in names/values.
- Use HttpOnly, Secure and SameSite for session cookies.
- Add CSRF protection in middleware for cookie-authenticated APIs; the framework does not provide it automatically.
