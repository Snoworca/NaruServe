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

- 슬래시 없는 `/docs` 요청의 처리(Express `static`처럼 `/docs/`로 301 리다이렉트할지)는 **(미확정)** 입니다.
- `GET`과 `HEAD`만 받습니다.
- **명시 라우트가 먼저**입니다. `app.get("/api/x", ...)`가 있으면 같은 경로의 파일보다 우선합니다. 파일도 없으면 `404`입니다.
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
});
```

| 옵션 | 기본값 | 설명 |
|---|---|---|
| `index(name)` | `index.html` | 폴더 요청 때 보낼 파일 |
| `directoryListing(boolean)` | `false` | 폴더 목록 표시. 보안상 기본 꺼짐 |
| `spaFallback(boolean)` | `false` | 없는 경로의 HTML 탐색 요청에 `index.html`을 보냅니다. |
| `cache(pattern, policy)` | — | 경로 패턴별 캐시 정책. 패턴 문법의 세부(마운트 접두사 기준 여부, `**`의 0개 세그먼트 매칭, 여러 패턴이 맞을 때의 우선순위)는 **(미확정)** |
| `mime(ext, type)` | — | 이 마운트에만 쓸 MIME |

### SPA fallback

React·Vue 같은 SPA의 클라이언트 라우팅을 위해 `/dashboard/settings` 같은 경로에 `index.html`을 보냅니다.
다음 조건을 **모두** 만족할 때만 fallback합니다.

- 그 경로에 실제 파일이 없음
- 명시 라우트나 `app.ws` 경로가 아님
- 브라우저의 HTML 탐색 요청(`Accept`에 `text/html`)

그래서 `/api/missing`에 대한 `fetch` 요청은 `index.html`이 아니라 `404`를 받습니다.
SPA fallback은 `/api/*`, `/ws/*`와 충돌하지 않아야 합니다(사양서 요구). 기본 제외 접두사(`/api`, `/ws` 등)와 그 설정 API는 **(미확정)** 입니다.

### 막혀 있는 것

- `..`, `%2e%2e`, `%2f`, 이중 인코딩, `NUL`로 루트 밖에 나가는 요청
- 심볼릭 링크(루트 안을 가리켜도 기본 거부. 허용 설정은 **(미확정)**)
- `.git`, `.env` 같은 점(.)으로 시작하는 숨김 파일
- 폴더 목록

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
| 그 밖 | `application/octet-stream` |

텍스트 타입에는 `charset=UTF-8`이 붙습니다.

## 캐시 정책

`CachePolicy`는 `Cache-Control` 헤더 값을 만듭니다. `res.cache(...)`와 `options.cache(...)`에 씁니다.

```java
res.cache(CachePolicy.noStore()).json(privateData);
res.cache(CachePolicy.publicFor(1, TimeUnit.HOURS)).json(publicData);
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
