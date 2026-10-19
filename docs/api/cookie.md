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
| `getMaxAge()` / `setMaxAge(int)` | `Max-Age` (초). 기본값 `-1`. 음수면 `Max-Age`를 쓰지 않아 브라우저를 닫을 때 사라지는 세션 쿠키가 되고, `0`이면 삭제, 양수면 그 초만큼 유지합니다. Servlet `Cookie`의 `maxAge` 규칙을 그대로 따른 것입니다. |
| `getSecure()` / `setSecure(boolean)` | `Secure` |
| `isHttpOnly()` / `setHttpOnly(boolean)` | `HttpOnly` |
| `getSameSite()` / `setSameSite(SameSite)` | `SameSite` |

`SameSite`는 `STRICT`, `LAX`, `NONE`입니다. 브라우저는 `SameSite.NONE`인 쿠키를 `Secure` 없이 받지 않습니다.
v1은 `Expires` 속성을 쓰지 않습니다. 수명은 `Max-Age`로만 정하며, 모든 현대 브라우저가 `Max-Age`를 지원하고 서버와 클라이언트 시계가 어긋나도 영향을 받지 않는다는 것이 그 이유입니다.
v1에는 서명 쿠키(Express `cookie-parser`의 `secret`, `req.signedCookies`)가 없습니다. 사양서에 비밀 키 관리 API가 없어서이며, 위조를 막으려면 앱이 미들웨어에서 값에 HMAC 같은 서명을 붙이고 검사하세요.

getter 이름은 Servlet `Cookie`를 따랐습니다(`getSecure()`, `isHttpOnly()`). Kotlin 프로퍼티 이름은 `secure`, `isHttpOnly`입니다.

## 값 인코딩

NaruServe는 쿠키 값을 인코딩하지도 디코딩하지도 않습니다. Express의 `res.cookie`는 값을 자동으로 URL 인코딩하지만, NaruServe는 받은 문자열을 그대로 씁니다. 값은 RFC 6265의 cookie-octet(공백, `"`, `,`, `;`, `\`, 제어 문자를 뺀 ASCII)만 쓸 수 있고, 그 밖의 문자는 앱이 직접 인코딩합니다(예: `URLEncoder`). 이렇게 정한 이유는 인코딩 방식을 프레임워크가 정하면 다른 서버가 만든 쿠키와 값이 달라지기 때문입니다.

```java
Cookie cookie = new Cookie("name", java.net.URLEncoder.encode("홍길동", "UTF-8"));
res.addCookie(cookie);
```

이름이 토큰이 아니거나 값에 허용되지 않는 문자가 있으면 `new Cookie(...)`, setter, `res.addCookie`가 `IllegalArgumentException`을 던집니다.

## 읽기

```java
Cookie session = req.cookie("session");   // 없으면 null
Cookie[] all = req.getCookies();          // 없으면 빈 배열
```

요청의 `Cookie` 헤더는 너그럽게 읽습니다. 이름이 토큰이 아니거나 `=`이 없는 항목은 요청을 실패시키지 않고 건너뜁니다. 값은 받은 그대로(따옴표 제거·퍼센트 디코딩 없이) 돌려줍니다.

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
