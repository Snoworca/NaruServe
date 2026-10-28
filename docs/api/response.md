# Response

[English](en/response.md) | [문서 목록](README.md)

`Response`는 Express의 `res`에 해당합니다. `status`, `header`, `addCookie`, `cache`는 `Response`를 돌려주므로 Express처럼 이어서 씁니다.

```java
res.status(201).header("X-Request-ID", id).json(created);
```

아래 공통 HTTP 동작은 NaruServe 0.10.0 기준입니다.

## 메서드 요약

### 상태와 헤더

| 메서드 | 반환 | Express | 설명 |
|---|---|---|---|
| `status(code)` | `Response` | `res.status(code)` | 상태 코드. 기본은 `200`. `100`–`999` 밖이면 `IllegalArgumentException` |
| `header(name, value)` | `Response` | `res.set(name, value)` | 응답 헤더. 같은 이름(대소문자 무시)이 있으면 바꿔 씁니다. 이름이 HTTP 토큰이 아니거나(빈 이름, 공백·`:` 포함 등) 값에 CR/LF·NUL 같은 제어 문자(탭 제외)나 U+00FF를 넘는 문자(헤더는 ISO-8859-1, 한 글자 1바이트)가 있으면 `IllegalArgumentException` |
| `cookie(name, value[, configurer])` | `Response` | `res.cookie(...)` | 기본 Path=/인 쿠키. 옵션은 `CookieOptions`로 설정합니다. |
| `clearCookie(name[, configurer])` | `Response` | `res.clearCookie(...)` | 같은 Path/Domain의 쿠키를 Max-Age=0으로 삭제합니다. |
| `addCookie(cookie)` | `Response` | `res.cookie(...)` | `Set-Cookie` 추가. [Cookie](cookie.md) |
| `cache(policy)` | `Response` | `res.set('Cache-Control', ...)` | `Cache-Control`을 정합니다. 보내기 전에 다시 부르면 마지막 값이 이깁니다. [캐시](static.md#캐시-정책) |
| `location(url)` | `Response` | `res.location(url)` | Location 헤더. 빈 주소나 유효하지 않은 헤더 값은 거부합니다. |
| `type(value)` | `Response` | `res.type(value)` | MIME 문자열 또는 `png`·`.PNG` 같은 확장자. 기본 MIME 표에 없는 확장자는 `application/octet-stream`입니다. |
| `isCommitted()` | `boolean` | `res.headersSent` | 상태·헤더를 이미 보냈는지 |

### 본문 보내기

| 메서드 | Content-Type | Express | 설명 |
|---|---|---|---|
| `json(object)` | `application/json; charset=UTF-8` | `res.json(o)` | 설정된 `JsonCodec`으로 직렬화 |
| `send(Object)` — 문자열 | `text/plain; charset=UTF-8` | `res.send('text')` | 텍스트. 직접 지정한 Content-Type이 있으면 그것을 씁니다. |
| `html(String)` | `text/html; charset=UTF-8` | `res.send('<p>..</p>')` | HTML |
| `send(Object)` — 바이트 배열 | 직접 지정. 없으면 `application/octet-stream` | `res.send(buffer)` | 바이트 |
| `stream(InputStream, contentType)` | 인자 | `stream.pipe(res)` | 헤더를 확정하고, 최초 콜백 흐름이 끝난 뒤 복사합니다. 그 이후의 호출은 호출 스레드에서 복사할 수 있습니다. 성공·실패·`abort` 어느 경우든 프레임워크가 입력을 한 번 닫습니다. |
| `file(File)` | 확장자로 결정 | `res.sendFile(path)` | 파일. Range·조건부 요청 지원. Express `res.sendFile`처럼 `Content-Disposition`을 보내지 않습니다 |
| `download(File, filename)` | 확장자로 결정 | `res.download(path, name)` | `Content-Disposition: attachment` |
| `end()` | — | `res.end()` | 본문 없이 끝냅니다. |
| `sse()` | `text/event-stream` | — | SSE 스트림 시작. `SseClient` 반환. [SSE](sse.md) |
| `abort(error)` | — | `res.destroy()` | 응답을 비정상 종료합니다(연결 끊기). 이미 끝났거나 끊긴 응답에서는 아무것도 하지 않습니다. |

`send(Object)` — 바이트 배열에 `Content-Type`이 없으면 Express가 `Buffer`에 그러듯 `application/octet-stream`을 씁니다. `stream(InputStream, contentType)`은 스트림의 소유권을 가져가서 성공·실패·`abort` 어느 경우든 닫습니다. [스트림](#스트림)

### 값의 타입에 따른 `send` 응답

NaruServe의 `send(Object)`는 실제 값이 문자열이면 텍스트, `byte[]`이면 바이너리, 객체·목록·숫자·불리언이면 JSON을 보냅니다. `Object` 변수에 담아도 같은 규칙입니다. `send(null)`은 빈 본문으로 끝내며, JSON `null`을 보내려면 `json(null)`을 사용합니다. HTML은 `html(...)`로 명시합니다.

| 하고 싶은 것 | Express | NaruServe |
|---|---|---|
| 텍스트 | `res.send('ok')` (→ `text/html`) | `res.send("ok")` (→ `text/plain`) |
| HTML | `res.send('<b>ok</b>')` | `res.html("<b>ok</b>")` |
| JSON 객체 | `res.send({ok: true})` 또는 `res.json(...)` | `res.send(map)` 또는 `res.json(map)` |
| JSON 문자열 | `res.json('hello')` | `res.json("hello")` → 본문 `"hello"` |

## 리다이렉트와 상태 응답

```java
res.redirect("/login");                    // 302, Location, empty body
res.redirect(307, "/login");               // method-preserving redirect
res.status(201).location("/users/7").send(user);
res.type("png").send(new byte[] {1, 2});    // image/png
res.sendStatus(404);                        // 404, plain-text Not Found
```

`redirect`는 301·302·303·307·308만 허용하며 상태와 주소를 모두 검증한 후 응답을 바꿉니다. 주소의 CR/LF 등은 기존 헤더 검증 규칙으로 거부합니다. `sendStatus`는 공유 HTTP 상태 설명을 텍스트로 보냅니다. 설명이 없는 상태는 `Error`, 204·304와 HEAD 요청은 빈 본문입니다. 이 함수들은 응답 완료 후에는 기존 쓰기 메서드와 같이 예외를 냅니다.

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

직렬화 중 예외가 나면, 아직 응답을 보내지 않은 경우에만 오류 응답으로 바뀝니다.

## 한 번만 보냅니다

응답은 한 번만 확정(commit)됩니다. 상태는 `NEW → COMMITTED → COMPLETED`(스트리밍 응답은 `COMMITTED → STREAMING → COMPLETED`)로 진행하고, 비정상 종료 시 `ABORTED`가 됩니다.

- 본문을 보낸 뒤 `status`, `header`, `addCookie`를 부르거나 다시 `json`/`send`를 부르면 `IllegalStateException`이 납니다(메시지에 현재 상태가 들어갑니다). `isCommitted()`와 `req`의 읽기 메서드는 응답이 끝난 뒤에도 그대로 씁니다.
- 공통 HTTP에서 응답을 보내거나 다음으로 진행하지 않고 반환하면 요청은 대기합니다. `send`, `json`, `end` 등으로 명시적으로 끝내거나, 응답을 보관해 나중의 콜백에서 완료하세요. `next.run()`은 다음 계층으로 진행하며 응답을 자동 종료하지 않습니다.
- 예외와 `next.error(error)`는 등록 위치 이후의 오류 계층으로 전달됩니다. 확정 전 기본 최종 처리는 Java `Throwable` 정보와 스택을 이스케이프한 HTML이며 `Content-Security-Policy`와 `X-Content-Type-Options`를 포함합니다. 오류 미들웨어에서 JSON을 명시적으로 보낼 수 있습니다. 확정 후 처리되지 않은 오류는 진행 중 응답을 중단하고, 이미 완료됐다면 다시 확정하지 않고 로그로 관찰할 수 있습니다.
- `status(code)`는 `100`–`999` 밖의 코드(`0`, `-1`, `1000` 등)를 받으면 `IllegalArgumentException`을 던집니다. Express 5의 `res.status`가 같은 범위 밖에서 `RangeError`를 던지는 것과 같습니다.
- `204`, `304` 응답과 `HEAD` 요청에는 본문을 보내지 않습니다. `res.status(204).json(x)`의 `x`는 버려집니다.
- 나중의 콜백에서 JSON을 보내거나 스트림/SSE를 시작할 수 있습니다. 응답 완료와 요청 자원 정리는 별개이며, 반환한 미완료 `Deferred`는 응답 바이트 전송 후에도 입력과 업로드를 보유할 수 있습니다. [요청 소유권](lifecycle.md#지연-콜백과-요청-자원)을 참고하세요.

## 파일

```java
app.get("/report", (req, res) -> res.file(new File("/data/report.pdf")));

app.get("/export", (req, res) -> {
    res.cache(CachePolicy.noStore())
       .download(new File("/data/export.bin"), "export.bin");
});
```

- 큰 파일도 메모리에 올리지 않고 스트리밍합니다.
- `Range` 요청에 `206`으로 답합니다. 범위를 충족할 수 없으면(시작 위치 ≥ 파일 크기) `416`, 잘못된 형식이거나 여러 범위면 `Range`를 무시하고 `200`입니다. `ETag`/`Last-Modified` 조건부 요청에 `304`로 답합니다. [정적 파일](static.md#range와-조건부-요청)
- `file()`에 사용자 입력으로 만든 경로를 넘기지 마세요. 경로 이탈 검사는 `staticFiles`만 합니다.
- `res.file`/`res.download`는 넘긴 파일 자체가 심볼릭 링크이거나 일반 파일이 아니면(폴더, 없는 파일) 보내지 않고 HTTP `404` 오류를 순서가 있는 오류 경로로 전달합니다. 처리되지 않은 공통 HTTP 결과는 HTML입니다.
- `file()`이 `Content-Disposition`을 보내지 않는 이유는 Express `res.sendFile`과 같게 브라우저가 내용을 그대로 보여 주게 하려는 것입니다. 저장 대화상자가 필요하면 `download()`를 쓰세요.
- `download()`의 `filename`은 헤더 인젝션 문자를 걸러서 씁니다.

### 기본 캐시 정책

`cache(policy)`를 부르지 않은 응답에 붙일 `Cache-Control`은 `app.defaultCache(policy)`로 정합니다. 기본값은 `null`이라 아무 헤더도 붙이지 않고, 응답이 이미 `Cache-Control`을 가지고 있으면 그 값을 둡니다. [서버 기본 정책](static.md#서버-기본-정책)

```java
app.defaultCache(CachePolicy.noCache());
app.get("/me", (req, res) -> res.cache(CachePolicy.noStore()).json(Collections.singletonMap("id", 1)));
```

Kotlin:

```kotlin
app.defaultCache(CachePolicy.noCache())
app.get("/report") { req, res -> res.file(File("/data/report.pdf")) }
```

## 스트림

`stream(in, contentType)`은 상태와 헤더를 확정하므로 먼저 설정하세요. 이후 `status`/`header` 변경은 `IllegalStateException`입니다. 최초 복사는 콜백 흐름이 끝날 때까지 미룹니다. 최초 반환 시점을 지난 뒤에는 늦은 콜백 안의 호출도 호출 스레드에서 복사를 마친 뒤 반환할 수 있으므로 늦은 호출은 I/O 동안 대기할 수 있습니다. 모든 전송이 하나의 디스패치 스레드에서 실행된다고 가정하지 마세요. 입력은 NaruServe가 한 번 닫습니다. try-with-resources나 Kotlin `use`로 전송 전에 닫지 마세요. 반환한 미완료 `Deferred`가 요청 입력을 보유하더라도 이 응답 스트림의 소유권이 앱으로 돌아오지는 않습니다.

```java
app.get("/video", (req, res) -> {
    InputStream in = new FileInputStream(new File("/data/video.mp4"));
    res.stream(in, "video/mp4");   // NaruServe closes the stream
});
```

Kotlin (`use { }`로 감싸지 않습니다):

```kotlin
app.get("/video") { req, res ->
    res.stream(FileInputStream(File("/data/video.mp4")), "video/mp4")
}
```

Express: `fs.createReadStream(path).pipe(res)`.

## 상태 코드만 보내기

```java
res.sendStatus(204);   // empty body
res.sendStatus(404);   // Not Found
```

## 리다이렉트

`res.redirect(url)`은 기본 302 응답이며, `res.redirect(status, url)`로 상태를 지정합니다.

```java
res.redirect("/login");
```
