# Response

`Response`는 Express의 `res`에 해당합니다. `status`, `header`, `addCookie`, `cache`는 `Response`를 돌려주므로 Express처럼 이어서 씁니다.

```java
res.status(201).header("X-Request-ID", id).json(created);
```

## 메서드 요약

### 상태와 헤더

| 메서드 | 반환 | Express | 설명 |
|---|---|---|---|
| `status(code)` | `Response` | `res.status(code)` | 상태 코드. 기본은 `200`. `100`–`999` 밖이면 `IllegalArgumentException` |
| `header(name, value)` | `Response` | `res.set(name, value)` | 응답 헤더. 같은 이름(대소문자 무시)이 있으면 바꿔 씁니다. 이름이 HTTP 토큰이 아니거나(빈 이름, 공백·`:` 포함 등) 값에 CR/LF·NUL 같은 제어 문자(탭 제외)나 U+00FF를 넘는 문자(헤더는 ISO-8859-1, 한 글자 1바이트)가 있으면 `IllegalArgumentException` |
| `addCookie(cookie)` | `Response` | `res.cookie(...)` | `Set-Cookie` 추가. [Cookie](cookie.md) |
| `cache(policy)` | `Response` | `res.set('Cache-Control', ...)` | 캐시 정책. [캐시](static.md#캐시-정책) |
| `isCommitted()` | `boolean` | `res.headersSent` | 상태·헤더를 이미 보냈는지 |

### 본문 보내기

| 메서드 | Content-Type | Express | 설명 |
|---|---|---|---|
| `json(object)` | `application/json; charset=UTF-8` | `res.json(o)` | 설정된 `JsonCodec`으로 직렬화 |
| `send(String)` | `text/plain; charset=UTF-8` | `res.send('text')` | 텍스트. 직접 지정한 Content-Type이 있으면 그것을 씁니다. |
| `html(String)` | `text/html; charset=UTF-8` | `res.send('<p>..</p>')` | HTML |
| `send(byte[])` | 직접 지정. 없으면 `application/octet-stream` | `res.send(buffer)` | 바이트 |
| `stream(InputStream, contentType)` | 인자 | `stream.pipe(res)` | 곧바로 반환하고, 핸들러가 반환한 뒤 같은 스레드에서 끝까지 복사합니다. 성공·실패·`abort` 어느 경우든 스트림을 한 번 닫습니다(호출자가 닫지 않습니다). |
| `file(File)` | 확장자로 결정 | `res.sendFile(path)` | 파일. Range·조건부 요청 지원. `Content-Disposition` 정책(생략 또는 `inline`)은 **(미확정)** |
| `download(File, filename)` | 확장자로 결정 | `res.download(path, name)` | `Content-Disposition: attachment` |
| `end()` | — | `res.end()` | 본문 없이 끝냅니다. |
| `sse()` | `text/event-stream` | — | SSE 스트림 시작. `SseClient` 반환. [SSE](sse.md) |
| `abort(error)` | — | `res.destroy()` | 응답을 비정상 종료합니다(연결 끊기). 이미 끝났거나 끊긴 응답에서는 아무것도 하지 않습니다. |

`send(byte[])`에 `Content-Type`이 없으면 Express가 `Buffer`에 그러듯 `application/octet-stream`을 씁니다. `stream(InputStream, contentType)`은 스트림의 소유권을 가져가서 성공·실패·`abort` 어느 경우든 닫습니다. [스트림](#스트림)

### Express와 다른 점: `send`는 텍스트만

Express의 `res.send`는 인자 타입에 따라 HTML·JSON·바이너리를 알아서 고릅니다. NaruServe는 메서드로 구분합니다.

| 하고 싶은 것 | Express | NaruServe |
|---|---|---|
| 텍스트 | `res.send('ok')` (→ `text/html`) | `res.send("ok")` (→ `text/plain`) |
| HTML | `res.send('<b>ok</b>')` | `res.html("<b>ok</b>")` |
| JSON 객체 | `res.send({ok: true})` 또는 `res.json(...)` | `res.json(map)` |
| JSON 문자열 | `res.json('hello')` | `res.json("hello")` → 본문 `"hello"` |

## json

```java
res.json(user);                                        // POJO
res.json(Arrays.asList(user1, user2));                 // 배열
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
- 핸들러가 본문 없이 반환하면 그때까지 정한 상태와 헤더로 `end()`한 것과 같습니다. 핸들러가 예외를 던지면, 아직 아무것도 보내지 않았을 때는 정한 상태·헤더를 버리고 빈 본문 `500`으로 답하고, 스트리밍 중이면 연결을 끊습니다.
- `status(code)`는 `100`–`999` 밖의 코드(`0`, `-1`, `1000` 등)를 받으면 `IllegalArgumentException`을 던집니다. Express 5의 `res.status`가 같은 범위 밖에서 `RangeError`를 던지는 것과 같습니다.
- `204`, `304` 응답과 `HEAD` 요청에는 본문을 보내지 않습니다. `res.status(204).json(x)`의 `x`는 버려집니다.
- 핸들러가 반환하기 전에 응답을 끝내세요. `sse()`, `file()`, `download()`, `stream()`은 핸들러가 반환한 뒤에도 전송이 이어질 수 있습니다.

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
- `download()`의 `filename`은 헤더 인젝션 문자를 걸러서 씁니다.

## 스트림

`stream(in, contentType)`은 상태와 헤더를 그 자리에서 보내고(그 뒤의 `status`/`header`는 `IllegalStateException`) 곧바로 반환합니다. 상태와 헤더는 먼저 정하세요. 핸들러 안에서 부르면 핸들러가 반환한 뒤 요청 스레드에서 `in`을 끝까지 복사합니다. 스트림은 NaruServe가 닫으므로 try-with-resources로 감싸지 마세요. 감싸면 복사를 시작하기 전에 닫힙니다.

```java
app.get("/video", (req, res) -> {
    InputStream in = new FileInputStream(new File("/data/video.mp4"));
    res.stream(in, "video/mp4");   // 닫기는 NaruServe가 합니다
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
res.status(204).end();   // Express: res.sendStatus(204)
res.status(404).end();
```

## 리다이렉트

`res.redirect`는 **(미확정)** 입니다. 지금은 직접 씁니다.

```java
res.status(302).header("Location", "/login").end();
```
