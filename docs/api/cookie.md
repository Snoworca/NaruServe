# Cookie

Servlet의 `javax.servlet.http.Cookie`와 비슷한 NaruServe 자체 `Cookie` 클래스를 씁니다. `cookie-parser` 없이 바로 읽습니다.

## 보내기

```java
Cookie cookie = new Cookie("session", token);
cookie.setHttpOnly(true);
cookie.setSecure(true);
cookie.setPath("/");
cookie.setMaxAge(3600);            // 초
cookie.setSameSite(SameSite.LAX);
res.addCookie(cookie);
```

Express라면:

```javascript
res.cookie('session', token, { httpOnly: true, secure: true, path: '/', maxAge: 3600 * 1000, sameSite: 'lax' });
```

**`maxAge` 단위가 다릅니다.** Express는 밀리초, NaruServe는 초입니다(`Set-Cookie`의 `Max-Age`와 같음).

Kotlin에서는 프로퍼티 문법을 씁니다.

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

## 속성

| getter / setter | `Set-Cookie` 속성 |
|---|---|
| `getName()` | 이름 (생성자에서만 지정) |
| `getValue()` / `setValue(String)` | 값 |
| `getPath()` / `setPath(String)` | `Path` |
| `getDomain()` / `setDomain(String)` | `Domain` |
| `getMaxAge()` / `setMaxAge(int)` | `Max-Age` (초). `0`이면 삭제. 설정하지 않을 때의 기본값(`Max-Age`를 생략한 세션 쿠키인지)과 음수의 의미는 **(미확정)** |
| `getSecure()` / `setSecure(boolean)` | `Secure` |
| `isHttpOnly()` / `setHttpOnly(boolean)` | `HttpOnly` |
| `getSameSite()` / `setSameSite(SameSite)` | `SameSite` |

`SameSite`는 `STRICT`, `LAX`, `NONE`입니다. 브라우저는 `SameSite.NONE`인 쿠키를 `Secure` 없이 받지 않습니다.
`Expires` 속성은 선택 기능입니다(**(미확정)**).

getter 이름은 Servlet `Cookie`를 따랐습니다(`getSecure()`, `isHttpOnly()`). Kotlin 프로퍼티 이름은 `secure`, `isHttpOnly`입니다.

## 읽기

```java
Cookie session = req.cookie("session");   // 없으면 null
Cookie[] all = req.getCookies();          // 없으면 빈 배열
```

브라우저는 요청에 이름과 값만 보냅니다. **요청에서 읽은 쿠키의 `isHttpOnly()`, `getSecure()`, `getSameSite()`, `getPath()` 값은 의미가 없습니다.** 그 값으로 판단하지 마세요.

## 삭제

만들 때와 **같은 `Path`와 `Domain`** 으로, `maxAge`를 `0`으로 보냅니다. Express의 `res.clearCookie('session')`에 해당합니다.

```java
Cookie expired = new Cookie("session", "");
expired.setPath("/");
expired.setMaxAge(0);
res.addCookie(expired);
```

## 보안

- 이름과 값에 CR/LF 같은 헤더 인젝션 문자가 있으면 거부합니다.
- 세션 쿠키에는 `HttpOnly`, `Secure`, `SameSite`를 붙이세요.
- 쿠키 인증을 쓰는 API는 CSRF 방어가 필요합니다. 프레임워크가 자동으로 해 주지 않으므로 미들웨어로 구현하세요.
