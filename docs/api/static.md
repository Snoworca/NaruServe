# 정적 파일·MIME·캐시

## staticFiles

Express의 `app.use(express.static('public'))`에 해당합니다.

```java
app.staticFiles("/", new File("./www"));
```

이것만으로 다음이 동작합니다.

| 요청 | 파일 |
|---|---|
| `/` | `www/index.html` |
| `/assets/app.js` | `www/assets/app.js` |
| `/docs/` | `www/docs/index.html` |

- 슬래시 없는 폴더 요청 `/docs`는 Express `static`처럼 `/docs/`로 301 리다이렉트합니다. `Location`은 원래의 인코딩된 경로에 `/`를 붙이고 쿼리를 그대로 둡니다(`/docs?x=1` → `/docs/?x=1`). `//assets`처럼 빈 세그먼트가 있는 경로는 `404`이므로 `Location`이 `//`로 시작하는 일(다른 호스트로의 리다이렉트)이 없습니다.
- 마운트는 등록 순서대로 찾고, 한 마운트에 파일이 없으면 다음 마운트로 넘어갑니다.

### 라우트와 메서드

- **명시 라우트가 먼저**입니다. 정적 마운트는 명시 라우트 다음에 찾습니다. `app.get("/api/x", ...)`가 있으면 같은 경로의 파일보다 우선합니다. 파일도 없으면 `404`입니다.
- 경로는 맞지만 메서드가 다른 라우트가 있을 때(예: `POST /login`만 등록), `GET`/`HEAD`는 정적 마운트로 넘어가고(마운트에도 없으면 그 라우트의 `405`), 다른 메서드는 그 라우트의 `405`와 그 라우트의 `Allow`를 받습니다.
- WebSocket 라우트의 경로는 그 라우트의 것입니다. 같은 이름의 파일이 있어도 정적 마운트나 SPA fallback이 답하지 않습니다.
- 파일 시스템에서 답하는 경로(파일, 폴더 index, 폴더 목록, 슬래시 `301`)에 `OPTIONS`나 `GET`/`HEAD`가 아닌 메서드가 오면 `405`와 `Allow: GET, HEAD`입니다. 다만 알 수 없는 메서드와 꺼진 `TRACE`/`CONNECT`는 그보다 먼저 `501`입니다. 명시 라우트의 자동 `OPTIONS` `204`는 정적 경로에는 없습니다.
- 경로 검사(`..`, 인코딩된 `/`, 인코딩된 `\`(`%5C`), `NUL`, 이중 인코딩 포함)에 걸린 경로는 메서드와 상관없이 `400`입니다. `\`는 Windows에서 경로 구분자이므로 모든 플랫폼에서 같게 거부합니다.
- 그 밖의 경로(SPA fallback이 내용을 보낼 경로, `GET`이 `404`를 받는 거부된 심볼릭 링크나 루트 밖 경로 포함)는 `GET`/`HEAD`가 아니면 `404`입니다. 거부된 심볼릭 링크 폴더에는 슬래시 `301`도 주지 않습니다.
- 정적 경로에서 `405`를 주는 이유는 파일이 있는 경로에 쓰기 메서드가 왔음을 알려 주기 위해서이고, SPA fallback 경로에 `404`를 주는 이유는 없는 경로를 있는 것처럼 보이지 않게 하려는 것입니다.
- 접두사를 붙여 여러 폴더를 마운트할 수 있습니다: `app.staticFiles("/static", new File("./public"))` → `/static/a.css` = `public/a.css`.

### 옵션

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

| 옵션 | 기본값 | 설명 |
|---|---|---|
| `index(name)` | `index.html` | 폴더 요청 때 보낼 파일 |
| `directoryListing(boolean)` | `false` | 폴더 목록 표시. 보안상 기본 꺼짐. 목록에는 심볼릭 링크가 `allowSymlinks(true)`여도 나타나지 않습니다 |
| `spaFallback(boolean)` | `false` | 없는 경로의 HTML 탐색 요청에 `index.html`을 보냅니다. |
| `spaExclude(prefixes...)` | — | SPA fallback에서 뺄 경로 접두사를 더합니다. `/api/`, `/ws/`는 항상 빠집니다 |
| `cache(pattern, policy)` | — | 경로 패턴별 캐시 정책. 패턴은 마운트 접두사를 뺀, `/`로 시작하는 경로에 맞춥니다. `/`로 시작하지 않는 패턴은 앞에 `**/`가 붙은 것처럼 맞춥니다. `*`는 세그먼트 하나 안에서, `**`는 0개 이상의 세그먼트에 맞으므로 `**/*.html`은 `/index.html`에도 맞습니다. 여러 패턴이 맞으면 먼저 등록한 패턴이 이깁니다 |
| `mime(ext, type)` | — | 이 마운트에만 쓸 MIME. 확장자의 앞 점은 있어도 없어도 됩니다 |
| `allowSymlinks(boolean)` | `false` | 루트 아래의 심볼릭 링크를 보낼지 |

옵션은 `listen` 때 마운트마다 복사해 고정합니다. 설정 함수가 받은 `options`를 붙잡아 두었다가 `listen` 뒤에 바꿔도 실행 중인 마운트는 바뀌지 않습니다.

`cache(pattern, policy)` 예: `/static` 마운트에서 `/assets/**`는 `/static/assets/x.js`에, `*.css`는 `/static/a/b.css`에 맞습니다. 먼저 등록한 패턴이 이기게 정한 이유는 Express 미들웨어처럼 위에서 아래로 읽히게 하려는 것입니다.

### SPA fallback

React·Vue 같은 SPA의 클라이언트 라우팅을 위해 `/dashboard/settings` 같은 경로에 `index.html`을 보냅니다.
다음 조건을 **모두** 만족할 때만 fallback합니다.

- 그 경로에 실제 파일이 없음
- 명시 라우트나 `app.ws` 경로가 아님
- 브라우저의 HTML 탐색 요청(`Accept`에 `text/html`)

그래서 `/api/missing`에 대한 `fetch` 요청은 `index.html`이 아니라 `404`를 받습니다.
SPA fallback은 `/api/`, `/ws/` 아래 경로(`/api`, `/ws` 자신 포함)에는 항상 동작하지 않습니다. `options.spaExclude("/admin")`으로 뺄 접두사를 더할 수 있고, 더해도 `/api/`, `/ws/`는 그대로 빠집니다. 접두사는 세그먼트 경계에서 맞추므로 `/apix`는 빠지지 않습니다. `/api/`, `/ws/`를 항상 빼는 이유는 API 오타나 WebSocket 경로에 HTML이 돌아가 클라이언트가 JSON 대신 `index.html`을 해석하는 일을 막기 위해서입니다.
여러 마운트가 있으면 모든 마운트에서 파일을 못 찾은 뒤에만 fallback하고, 경로가 맞는 첫 `spaFallback(true)` 마운트의 index를 보냅니다.

### 막혀 있는 것

- `..`, `%2e%2e`, `%2f`, `%5C`(`\`), 이중 인코딩, `NUL`로 루트 밖에 나가는 요청
- 심볼릭 링크(루트 안을 가리켜도 기본 거부. `allowSymlinks(true)`로 허용하면 루트 아래의 링크를 보내지만, 링크의 실제 대상이 마운트 루트의 정규 경로 밖이면 여전히 `404`입니다)
- `.git`, `.env` 같은 점(.)으로 시작하는 숨김 파일
- 폴더 목록

거부한 파일은 없는 파일과 같이 `404`입니다. 심볼릭 링크를 기본으로 막는 이유는 루트 안에 만든 링크 하나로 루트 밖 파일이 공개되는 일을 막기 위해서입니다.

`java.io`만 쓰는 resolver는 파일을 연 뒤의 재검사를 열린 파일 디스크립터에 묶을 수 없으므로, 재검사와 읽기 사이에 경로 구성 요소를 바꿔치기하는 공격(TOCTOU)은 남은 한계입니다. 또 Java 8 JVM은 정규 경로(canonical path)를 최대 30초 캐시하므로 이 검사가 바로 바뀐 링크를 보도록 하려면 `-Dsun.io.useCanonCaches=false`로 실행하세요. Android에는 이 캐시가 없습니다.

정적 파일 응답에는 `X-Content-Type-Options: nosniff`가 붙습니다.
DB 파일, 키, 설정 파일을 정적 폴더에 두지 마세요. 공개할 파일만 담은 폴더를 마운트하세요.

## MIME

### 우선순위

1. 마운트별 `options.mime(...)`
2. 전역 `app.mimeTypes().register(...)`
3. 내장 표
4. `application/octet-stream`

```java
app.mimeTypes()
   .register(".mydata", "application/x-mydata")
   .register(".custom", "text/plain");
```

MIME 타입이 빈 문자열이거나 CR, LF 같은 제어 문자를 담아 헤더 값으로 쓸 수 없으면 `register`와 `options.mime` 호출 때 `IllegalArgumentException`입니다. 요청마다 `500`이 나는 대신 설정 단계에서 바로 알 수 있게 하려는 것입니다.

### 내장 표

| 확장자 | MIME |
|---|---|
| `.html`, `.htm` | `text/html` |
| `.css` | `text/css` |
| `.js`, `.mjs` | `text/javascript` |
| `.json`, `.map` | `application/json` |
| `.webmanifest` | `application/manifest+json` |
| `.wasm` | `application/wasm` |
| `.svg` | `image/svg+xml` |
| `.png` | `image/png` |
| `.jpg`, `.jpeg` | `image/jpeg` |
| `.webp` | `image/webp` |
| `.avif` | `image/avif` |
| `.woff` | `font/woff` |
| `.woff2` | `font/woff2` |
| `.mp4` | `video/mp4` |
| `.webm` | `video/webm` |
| `.pdf` | `application/pdf` |
| `.txt` | `text/plain` |
| `.csv` | `text/csv` |
| `.xml` | `application/xml` |
| `.gif` | `image/gif` |
| `.ico` | `image/x-icon` |
| `.mp3` | `audio/mpeg` |
| `.ogg` | `audio/ogg` |
| 그 밖 | `application/octet-stream` |

텍스트 타입에는 `charset=UTF-8`이 붙습니다.

## 캐시 정책

`CachePolicy`는 `Cache-Control` 헤더 값을 만듭니다. `res.cache(...)`와 `options.cache(...)`에 씁니다.

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

### 서버 기본 정책

`app.defaultCache(policy)`는 `Cache-Control`을 정하지 않은 응답에 붙일 정책입니다. 기본값은 `null`이고, 그때는 `Cache-Control`을 붙이지 않습니다. 응답을 보내는 순간 `res.cache`, `res.header`, 맞는 `options.cache` 패턴, 프레임워크 자신 중 무엇이든 `Cache-Control`을 이미 정했으면 그 값을 그대로 둡니다. 기본값을 `null`로 둔 이유는 프레임워크가 앱 모르게 캐시 동작을 바꾸지 않게 하려는 것입니다.

```java
app.defaultCache(CachePolicy.noStore());   // 정하지 않은 응답은 모두 no-store
```

| 코드 | `Cache-Control` | 쓰는 곳 |
|---|---|---|
| `CachePolicy.noStore()` | `no-store` | 인증이 필요한 API, 개인 데이터 |
| `CachePolicy.noCache()` | `no-cache` | HTML (쓸 때마다 재검증) |
| `CachePolicy.privateFor(10, MINUTES)` | `private, max-age=600` | 사용자별 데이터 |
| `CachePolicy.publicFor(1, HOURS)` | `public, max-age=3600` | 누구에게나 같은 데이터 |
| `.immutable()` | `..., immutable` 추가 | 파일 이름에 해시가 들어간 JS/CSS만 |

권장 조합:

| 리소스 | 정책 |
|---|---|
| 인증된 REST 응답 | `noStore()` |
| HTML | `noCache()` (+ ETag 자동) |
| `app.3f9a2c.js` 같은 해시 파일 | `publicFor(365, DAYS).immutable()` |
| 개인 파일 다운로드 | `privateFor(...)` 또는 `noStore()` |

사용자마다 다른 응답에 `publicFor`를 쓰지 마세요. 공유 캐시(프록시·CDN)가 다른 사용자에게 보낼 수 있습니다.

## Range와 조건부 요청

`staticFiles`, `res.file`, `res.download`는 다음을 처리합니다. 큰 영상·파일의 이어받기와 탐색에 필요합니다.

| 요청 | 응답 |
|---|---|
| `If-None-Match`가 현재 `ETag`와 같음 | `304` (본문 없음). `If-Modified-Since`보다 우선 |
| `If-Modified-Since` 이후 바뀌지 않음 | `304` |
| `Range: bytes=0-1023` (올바른 범위 하나) | `206` + `Content-Range` |
| 끝이 파일 크기를 넘는 범위 (`bytes=0-99999`, 파일 1000바이트) | 끝을 잘라서 `206` |
| 시작 위치가 파일 크기 이상인 범위 (`bytes=5000-`, 파일 1000바이트) | `416` + `Content-Range: bytes */크기` |
| 범위 여러 개 (`bytes=0-1,5-9`) | `Range`를 무시하고 `200` 전체 응답 |
| `If-Range`가 현재 값과 다름 | `200` 전체 응답 |

응답에는 `Accept-Ranges: bytes`, `ETag`, `Last-Modified`가 붙습니다. 약한 ETag(`W/"..."`)는 `If-Range` 비교에 쓰지 않습니다.
파일은 메모리에 올리지 않고 스트리밍합니다.
