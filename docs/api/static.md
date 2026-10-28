# 정적 파일·MIME·캐시

[English](en/static.md) | [문서 목록](README.md)

> **버전 범위:** 이 문서는 0.10.0 대상 체크아웃의 동작을 설명하며 Maven 배포 완료를 뜻하지 않습니다.

## staticFiles

Express의 `app.use(express.static('public'))`와 같은 용도이며, NaruServe는 아래 파일 시스템 안전성·메서드 정책을 유지합니다.

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

- 0.10.0 공통 HTTP 스택에서 `staticFiles`는 **등록한 위치**에 서비스를 추가합니다. 앞선 라우트·미들웨어가 응답하면 그것이 이기고, 앞선 정적 서비스가 파일을 보내면 뒤의 라우트·미들웨어는 실행하지 않습니다. 정적 등록 앞에 인증을 두세요. 모든 명시 라우트 뒤에 정적 파일을 따로 찾는 전역 단계는 없습니다.
- 파일이 없으면 요청 뷰를 유지한 채 다음 등록 계층으로 넘어갑니다. 메서드가 다른 라우트는 일반 HTTP 경로를 독점하지 않습니다. `GET`/`HEAD`는 뒤의 정적 서비스에 도달할 수 있고, 끝까지 응답하지 않은 메서드·경로는 그 라우트의 전역 `405`가 아닌 공통 HTML `404`입니다.
- 실제로 유지되는 WebSocket 전용 경로는 정적 등록 순서와 무관하게 예약됩니다. 파일이나 SPA fallback이 `426`·기존 OPTIONS·메서드 동작을 대신하지 않습니다. 같은 경로의 공통 HTTP 라우트는 공통 스택 순서를 따릅니다. [WebSocket](websocket.md)을 참고하세요.
- 도달한 정적 서비스는 실제 파일·폴더 index·폴더 목록·슬래시 리다이렉트에 `GET`/`HEAD`로 답합니다. `OPTIONS`·`TRACE`·`CONNECT`·`COPY`를 포함한 다른 토큰은 실제 파일 시스템 리소스가 있으면 `405`와 `Allow: GET, HEAD`, 없으면 뒤 계층으로 넘어갑니다. 공통 HTTP에는 앞선 알 수 없는 메서드/TRACE/CONNECT `501` 가드가 없습니다. 별도 WS/엄격 프로필의 가드는 유지됩니다.
- 매칭되는 정적 서비스의 경로 검사(`..`, 인코딩된 `/`, 인코딩된 `\`(`%5C`), `NUL`, 이중 인코딩)에 걸리면 정적 메서드 처리 전에 `400`입니다. 파일 시스템 접근을 보호하는 검사이며 앞선 공통 라우트에 전역 검증을 부과하지 않습니다. `\`는 Windows에서 구분자이므로 모든 플랫폼에서 거부합니다.
- SPA 내용은 실제 쓰기 대상 리소스가 아닙니다. `GET`/`HEAD` 외 메서드의 파일 미일치는 뒤 계층으로 넘어가며, 끝까지 응답이 없으면 `404`입니다. 거부된 심볼릭 링크 폴더에는 슬래시 `301`도 주지 않습니다.
- `app.staticFiles("/static", new File("./public"))`는 `/static/a.css`를 `public/a.css`로 연결합니다. 이 파일 시스템 마운트 접두사는 조회·캐시 매칭에서 제거하며 공통 `Router` 마운트와 별개입니다. 정적 미일치가 뒤 계층의 경로에서 접두사를 지운 채 남기지 않습니다.

정적 안전성·메서드 거부는 설정된 오류 렌더러(기본 표준 JSON)를 유지합니다. 파일 열기·읽기에서 던진 예외는 뒤쪽에 등록된 오류 계층으로만 가며 오류로 전환할 때 파일 전용 헤더를 지웁니다. 커밋 뒤의 실패는 두 번째 응답을 보내지 않습니다. 처리하지 않은 공통 HTTP 오류와 일반 라우트 미일치는 [finalhandler HTML](defaults.md#기본-오류-본문과-노출-정보)입니다.

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

React·Vue 클라이언트 라우팅에서는 도달한 정적 서비스가 실제 파일 없는 `GET`/`HEAD` 경로(`/dashboard/settings` 등)에 `index.html`을 보낼 수 있습니다. `Accept`에 `text/html`이 있어야 하며 `*/*`만으로는 부족합니다. 앞선 HTTP 계층이 응답하면 서비스에 도달하지 않습니다. **뒤에** 등록한 명시 라우트가 앞선 SPA 서비스를 자동으로 이기지는 않으므로 API 라우트를 먼저 두거나 제외 경로를 추가하세요. 유지되는 WS 전용 경로는 항상 예약됩니다.

SPA fallback은 `/api/`, `/ws/` 아래 경로(`/api`, `/ws` 자신 포함)에서 항상 제외되어 API/WebSocket 요청이 SPA 문서를 받지 않게 합니다. `options.spaExclude("/admin")`은 제외 접두사를 추가하며 기본값을 지우지 않습니다. 세그먼트 경계에서 맞추므로 `/apix`는 제외하지 않습니다. 여러 마운트에서는 실제 파일 조회가 SPA 내용보다 우선하며, 모든 마운트에 파일이 없을 때 먼저 도달한 매칭 `spaFallback(true)` 서비스가 index를 보낼 수 있습니다.

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
app.defaultCache(CachePolicy.noStore());   // no-store for responses without an explicit policy
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
