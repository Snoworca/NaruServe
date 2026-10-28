# 라우팅

[English](en/routing.md) | [문서 목록](README.md)

이 문서는 **0.10.0 체크아웃의 일반 HTTP 프로필**을 설명합니다. 기존 `routing.Router`와 WebSocket 업그레이드의 엄격 규칙은 [별도 프로필](#기존-라우터와-websocket)로 유지합니다. 배포 좌표의 공개 여부는 [설치 안내](README.md)에서 확인하세요.

## 라우트 등록

| 호출 | 용도 |
|---|---|
| `app.get(path, handler)` | 두 인자 `RouteHandler`로 응답 |
| `app.get(path, middleware...)` | 세 인자 `Middleware` 콜백을 순서대로 실행 |
| `app.route(path)` | 그 위치에 하나의 Route를 만들고 같은 builder에 메서드·콜백 추가 |
| `app.route(MethodSet, path, handler)` | 기존 enum 메서드 집합 편의 API |
| `app.method("COPY", path, callbacks...)` | enum 밖의 유효한 HTTP 메서드 등록 |

### 메서드별 핸들러

```java
app.route("/users/:id")
   .get(getUser)
   .put(updateUser)
   .delete(deleteUser);
```

이 builder는 **하나의 Route 식별자와 스택 위치**를 공유합니다. 세 번의 독립 `app.get`/`put`/`delete` 등록과 항상 같은 제어 흐름은 아닙니다. `route(path)`를 호출한 시점에 위치가 정해지고, 나중에 builder에 콜백을 더해도 위치는 옮겨지지 않습니다. `RouteBuilder`의 `get`, `post`, `put`, `patch`, `delete`, `head`, `options`, `method(token, Middleware...)`, `use(Middleware)`는 같은 builder를 반환합니다. `use`는 그 Route의 모든 메서드에 적용되는 콜백을 현재 위치에 추가합니다. 앞서 등록한 응답 핸들러보다 먼저 실행되도록 재정렬하지 않습니다.

### 한 핸들러로 여러 메서드

아래 `find`, `create`, `remove`는 앱 메서드입니다. `default`의 405는 이 예제가 직접 선택한 응답이며 프레임워크의 일반 HTTP 기본값이 아닙니다.

```java
app.route(Method.get().post().delete(), "/users/:id", (req, res) -> {
    switch (req.method()) {
        case GET:
        case HEAD:   res.json(find(req.param("id"))); break;
        case POST:   res.status(201).json(create(req)); break;
        case DELETE: remove(req.param("id")); res.status(204).end(); break;
        default:     res.status(405).end();
    }
});
```

```kotlin
app.route(Method.get().post().delete(), "/users/:id") { req, res ->
    when (req.method()) {
        Method.GET, Method.HEAD -> res.json(find(req.param("id")))
        Method.POST -> res.status(201).json(create(req))
        Method.DELETE -> { remove(req.param("id")); res.status(204).end() }
        else -> res.status(405).end()
    }
}
```

## Method와 MethodSet

```java
public enum Method { GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE, CONNECT }
```

`Method`의 기존 아홉 상수는 유지합니다. `req.methodToken()`으로 실제 문자열을 읽으세요. `COPY`, `PROPFIND`처럼 enum에 없는 토큰에서 `req.method()`를 부르면 `IllegalStateException`입니다. `method(token, path, callbacks...)`는 토큰 문법을 검사하고 등록 이름을 대문자로 정규화합니다. 잘못된 토큰은 `IllegalArgumentException`, null은 `NullPointerException`입니다.

| 표현 | 집합 |
|---|---|
| `Method.get().post()` | GET, POST |
| `Method.of(Method.PUT, Method.PATCH)` | PUT, PATCH |
| `Method.any()` | GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS |
| `Method.any().except(Method.DELETE)` | 위 집합에서 DELETE 제외 |
| `set.with(Method.TRACE)` | TRACE를 명시적으로 추가 |

`Method.any()`는 Express `app.all`의 모든 토큰을 뜻하지 않습니다. 기존 유한 집합 편의 API입니다. 일반 HTTP에서 TRACE/CONNECT 등록을 위해 기존 WS용 정책 스위치를 켤 필요는 없습니다. 전송 계층에서 받는 request-target·Host·프레이밍 제약은 별개입니다.

### MethodSet 조회

`contains`, `size`, `iterator`를 제공합니다. 반복 순서는 enum 선언 순서이며 `remove()`는 지원하지 않습니다. `equals`/`hashCode`는 멤버로 비교하고 `toString()`은 `GET, POST` 형태입니다. 조합은 불변 집합을 반환하고 null 배열·원소는 거부합니다.

```java
MethodSet set = Method.of(Method.DELETE, Method.GET, Method.POST);
boolean canPost = set.contains(Method.POST);   // true
for (Method m : set) {                         // GET, POST, DELETE 순서
    System.out.println(m);
}
int n = set.size();                            // 3
```

```kotlin
val set = Method.get().post().except(Method.POST)
val canGet = Method.GET in set                 // true
for (m in Method.of(Method.DELETE, Method.GET)) {  // GET, DELETE 순서
    println(m)
}
val n = set.size()                             // 1
```

## 경로 패턴

문자열은 원래 **raw pathname**에 맞춥니다. 쿼리는 제외합니다. 전체 경로를 미리 디코딩하지 않으며, 매칭한 캡처만 엄격한 UTF-8로 한 번 디코딩합니다.

| 문자열 패턴 | 의미 |
|---|---|
| `/users/:id` | 슬래시 하나를 넘지 않는 필수 값 |
| `/files/*parts` | 하나 이상의 세그먼트, `List<String>` 와일드카드 |
| `/files{/*parts}` | `/files`도 허용, 빠진 와일드카드 키는 생략 |
| `/users{/:id}` | 중괄호 그룹 전체가 선택적 |
| `/:file{.:ext}` | 같은 세그먼트 안의 리터럴 구분자와 선택적 확장자 |
| `/:"user-id"` 또는 `/*"file-parts"` | 따옴표로 감싼 이름 |

이름은 지원되는 Unicode 식별자 또는 따옴표 이름입니다. `\`로 예약 문자를 리터럴로 이스케이프합니다. Express 4의 `?`, `+`, 괄호 내 정규식 같은 문자열 문법은 그대로 쓰지 않습니다. `/*`는 이름이 없어 오류이고 `/*parts`처럼 씁니다. 문자열 문법 오류는 등록 시 `RoutingConfigException`이며 위치 정보는 Unicode 코드 포인트 기준입니다. 동일 이름을 여러 번 캡처하면 **실제로 참여한 마지막 바인딩**이 값과 scalar/list 형태를 정합니다. 생략된 선택 그룹이 앞의 값을 지우지 않습니다.

`/a%20b` 같은 리터럴은 인코딩된 raw 경로와 비교합니다. `/caf%C3%A9`와 `/café`는 같은 리터럴이 아닙니다. `/literal/%ZZ`는 리터럴로 매칭할 수 있지만 `/:value`가 `%ZZ`를 캡처하면 디코딩 오류 400입니다. 와일드카드는 **리터럴 `/`로 먼저 나눈 뒤** 각 조각을 디코딩하므로 `/files/a%2Fb/c`의 값은 `["a/b", "c"]`입니다. 경로 캡처의 `+`는 그대로 `+`입니다. 정적 파일의 경로 이탈·심볼릭 링크 검사와 WS 경로 거부는 이 HTTP 캡처 규칙으로 완화되지 않습니다.

### 매칭 규칙

일반 HTTP 기본은 **대소문자 무시, 끝 슬래시 하나 허용**이며 반복 슬래시는 의미가 있습니다. `use(path, ...)`는 접두사를 매칭하되 문자열 경로의 세그먼트 경계를 지켜 `/api`가 `/apix`를 잡지 않습니다. 자식 `Router.create(mergeParams, caseSensitive, strict)`는 자기 옵션을 가집니다.

### 우선순위

**등록 순서**로 처리합니다. 더 구체적인 고정 경로를 앞세우지 않습니다. 앞 콜백이 응답하고 계속하지 않으면 뒤 라우트는 실행되지 않습니다. 중복·겹치는 일반 HTTP 라우트는 합법이며 `next.run()`/`next.route()`로 다음 후보를 선택합니다.

```java
app.get("/users/:id", getUser);
app.get("/users/me", getMe);     // reached only if the earlier route continues
```

위 코드는 `getUser`를 먼저 시도합니다. `getUser`가 계속하지 않으면 `getMe`는 실행되지 않습니다.

## Java Pattern과 경로 대안

`Object path`에는 문자열, `java.util.regex.Pattern`, `RegexPathPattern`, 이 값들의 중첩 `Object[]`·`List<?>`를 넘길 수 있습니다. 대안은 왼쪽부터 첫 매칭을 선택합니다. 모든 대안과 콜백을 검증한 뒤 등록하므로 잘못된 중첩 원소나 null 때문에 일부만 등록되지 않습니다. 문자열에 `"/a|/b"`를 써서 대안을 표현하지 않습니다.

정규식은 **Java Pattern**입니다. ECMAScript 정규식, 플래그, `lastIndex`를 에뮬레이션하지 않습니다. 원래 raw 경로에서 `Matcher.find()`로 검색하므로 전체 매칭이 필요하면 `^`·`$`를 명시하세요. 문자열의 case/strict 정책은 Pattern 플래그를 덮어쓰지 않습니다. use/마운트에서는 검색된 정규식도 위치0에서 시작해 세그먼트 경계에서 끝나는 실제 접두사여야 하며 그렇지 않으면 건너뜁니다. 이름 있는 그룹은 Java 이름을, 이름 없는 그룹은 이름 없는 그룹끼리의 0부터 시작하는 번호(`"0"`, `"1"`)를 키로 씁니다. 비참여 그룹은 생략하고 참여한 빈 그룹은 `""`로 남깁니다.

`RegexPathPattern.compile(Pattern)`은 보통의 Pattern을 그대로 지원합니다. 일부 내장 플래그 조합은 `Pattern.flags()`가 최초 COMMENTS/UNIX_LINES 설정을 잃어 캡처 이름 메타데이터가 모호할 수 있습니다. 이때 등록을 명확한 오류로 거부하며 `com.snoworca.naruserve.routing.RegexPathPattern.compile(source, originalFlags)`로 최초 소스와 플래그를 넘기세요. 리플렉션·JNI나 JavaScript 엔진은 필요 없습니다.

## 독립 Router, 마운트와 param 훅

`com.snoworca.naruserve.Router.create()`는 독립된 HTTP 스택입니다. 기존 `com.snoworca.naruserve.routing.Router`와 패키지가 다르므로 import를 명시하세요. `create()`는 `mergeParams=false`, `caseSensitive=false`, `strict=false`이고 `create(true)`는 부모 캡처를 자식에 합칩니다. 충돌한 이름은 자식 값이 이기며 연속된 이름 없는 숫자 키 묶음은 병합 시 이동합니다. 부모0/1·자식0/1이면 자식 값은2/3이 됩니다. 이동량은0부터 이어진 숫자 키를 세며01 같은 비정규 키는 보통 이름입니다. 같은 Router를 여러 위치에 마운트해도 요청의 마운트 문맥은 분리됩니다. 라이브러리가 만든 Router를 쓰세요. 임의의 사용자 구현을 내부 Router로 간주하지 않습니다.

`app.use(prefix, router)` 안에서는 `path()`·`url()`에서 실제 매칭 접두사가 빠지고 `baseUrl()`에 누적됩니다. `originalUrl()`·`rawPath()`는 원래 값입니다. 자식이 나가면 부모 뷰를 복원합니다. `use(prefix, middleware)`도 현재 콜백 안에서 접두사를 제거하며, 같은 Request·본문 캐시·속성·서비스를 공유합니다. 자세한 값은 [Request](request.md#마운트와-경로-뷰)를 보세요.

`app.param(name, ParamHandler)`와 `router.param`은 현재 라우터의 존재하는 캡처만 전처리합니다. 콜백은 `(req, res, next, List<String> originalValue, String name)`입니다. `req.param(name, value)` 또는 `req.paramValues(name, values)`로 후속 조회를 바꿀 수 있습니다. 실제 캡처 키 순서로 훅을 실행하며, 정수 인덱스 키 `0`부터 `4294967294`는 숫자 순, 나머지는 캡처 열거 순입니다. `01`·`4294967295`는 정수 인덱스가 아닙니다.

캐시는 요청·라우터 문맥별입니다. 같은 디코딩 scalar는 훅 결과를 재사용하고 list/wildcard는 참조 식별자를 비교하므로 새 캡처 리스트는 훅을 다시 실행할 수 있습니다. 생략된 선택 캡처와 부모에서 합친 값만으로 자식 훅을 실행하지 않습니다. 라우트 건너뛰기·오류·라우터 이탈 결과도 전파합니다. 훅이 저장한 `next`는 나중에 재개할 수 있습니다.

## 자동 응답

| 일반 HTTP 상황 | 결과 |
|---|---|
| 경로·메서드에 처리할 콜백이 없고 끝까지 진행 | 404 HTML, `Cannot METHOD /raw/path` |
| HEAD | 먼저 만난 Route 안에 HEAD가 없고 GET이 있으면 GET 사용; 전송 본문은 비움 |
| 자동 OPTIONS | 실제 순회한 현재 라우터의 메서드를 모아 200, `Allow`와 같은 평문 본문 |
| 유효한 확장 메서드 | 등록된 토큰으로 처리, 처리되지 않으면 404 |
| 응답·next 없이 콜백 반환 | 미완료 상태로 대기 |

HEAD는 뒤에 등록한 별도 HEAD 라우트가 앞의 GET Route를 뛰어넘지 않습니다. GET을 실행해도 `methodToken()`/`method()`는 HEAD입니다. GET과 같은 Content-Length를 유지하되 본문 바이트는 보내지 않습니다. 자동 OPTIONS는 router-local이며 자식에서 응답하면 부모 메서드를 더하지 않습니다. `Content-Type: text/plain`(charset 없음), `X-Content-Type-Options: nosniff`이고 OPTIONS를 무조건 Allow에 더하지 않습니다. CORS preflight의 204와는 다릅니다.

## 정적 파일·WebSocket과의 관계

일반 HTTP는 `use`, 라우트, 정적 마운트, 오류 레이어를 **같은 등록 순서**로 순회합니다. 정적 파일 미스는 뒤로 계속합니다. 인증보다 앞에 등록한 정적 마운트는 먼저 응답할 수 있으므로 순서를 점검하세요. [미들웨어](middleware.md), [정적 파일](static.md)

## 라우터 정책

기존 `app.routerPolicy(RouterPolicy)`를 명시하면 일반 HTTP에도 case·끝 슬래시·반복 슬래시 설정을 적용합니다. 라우트 등록 뒤라도 `listen` 전이면 적용됩니다. 이 호출을 생략한 일반 HTTP 기본과 `RouterPolicy.strict()`는 다릅니다.

| 옵션 | 명시 정책의 의미 |
|---|---|
| `mergeSlashes(true)` | 매칭에서 반복 슬래시 합침; raw 경로·실제 마운트 접두사 뷰는 보존 |
| `ignoreTrailingSlash(true)` | 문자열 끝 슬래시 허용 |
| `caseInsensitive(true)` | 문자열 대소문자 무시 |
| `enableTrace` / `enableConnect` | 기존 엄격 라우터·WS 프로필의 메서드 게이트 |

```java
app.routerPolicy(RouterPolicy.builder()
    .mergeSlashes(true)
    .ignoreTrailingSlash(true)
    .caseInsensitive(false)
    .enableTrace(false)
    .enableConnect(false)
    .build());
```

```kotlin
app.routerPolicy(
    RouterPolicy.builder()
        .ignoreTrailingSlash(true)
        .caseInsensitive(true)
        .build()
)
```

`RouterPolicy`는 불변입니다. builder 스위치는 모두 false이며 `strict()`도 같은 엄격 값입니다. `routerPolicy(null)`은 null 오류이고 설정은 NEW에서만 바꿉니다. Pattern의 자체 플래그는 바꾸지 않습니다.

## 기존 라우터와 WebSocket

직접 사용하는 `routing.Router`/`RoutePattern`은 기존 세그먼트 문법·고정 경로 우선·충돌 검사·엄격 정책을 유지합니다. `app.ws`의 경로·Origin·업그레이드 검증도 일반 HTTP 문자열 문법으로 교체하지 않습니다. 유효한 기존 전역·접두사 인증 미들웨어는 WS 업그레이드 전에 계속 실행됩니다. 고급 HTTP Pattern·대안·독립 Router·listen 이후의 HTTP 추가가 WS에 같은 동작을 제공한다는 뜻은 아닙니다.

WS 전용 경로의 보통 GET/HEAD는 426, 자동 OPTIONS는 204이며 기존 비활성/알 수 없는 메서드 게이트는 501입니다. 잘못된 인코딩·인코딩된 슬래시/NUL·경로 이탈을 가진 업그레이드는 엄격하게 거부합니다. 일반 HTTP 캡처 규칙이나 정적 파일이 이 경계를 우회하지 않습니다. [WebSocket](websocket.md)

## 독립 Router와 대안 예제

이 예제는 세 경로 대안에서 지역 id 훅을 실행하고 마운트한 실제 baseUrl을 사용합니다. X-Mode: skip은 그 Route를 건너뜁니다. Pattern에는 전체 매칭 anchor를 명시합니다.

```java
com.snoworca.naruserve.Router api = com.snoworca.naruserve.Router.create(true);
api.param("id", (request, response, next, values, name) -> {
    request.param(name, values.get(0).toUpperCase(java.util.Locale.ROOT));
    next.run();
});
Object paths = new Object[] {
    "/users/:id{/:tab}",
    java.util.regex.Pattern.compile("^/legacy/(?<id>[0-9]+)$"),
    com.snoworca.naruserve.routing.RegexPathPattern.compile("^/item/(?<id>[0-9]+)$", 0)
};
api.get(paths, (request, response, next) -> {
    if ("skip".equals(request.header("X-Mode"))) {
        next.route();
        return;
    }
    response.send(request.baseUrl() + ":" + request.param("id"));
});
app.use("/api", api);
```

## 저수준 매처 조회

`com.snoworca.naruserve.routing.HttpPathPattern.compile(String path, boolean end, boolean caseSensitive, boolean trailing)`는 전체/세그먼트 경계 접두사·대소문자·선택 끝 슬래시를 명시합니다. 문법/선택 그룹 확장 오류는 `RoutingConfigException`이고 선택 대안 256개를 넘으면 거부합니다. `match(rawPath)`는 null 또는 불변 Match를 반환하며 `matchedPath()`, raw `captures(): Map<String,List<String>>`, `wildcards(): Set<String>`를 제공합니다. 여기의 scalar는 singleton 목록이고 wildcard는 디코딩 전에 나눕니다. wildcard 집합은 선언된 키만이 아니라 마지막 참여 바인딩을 반영합니다. `keys()`는 선택/반복 선언도 포함한 문법 순서 Key 메타데이터(`name()`, `wildcard()`)입니다. 이 재사용 가능한 엔진 독립 매처는 퍼센트 디코딩이나 기존 라우터 변경을 하지 않습니다. 조회/컴파일 API이며 등록 `Object path`가 받는 추가 타입은 아닙니다.

`RegexPathPattern.keys()`는 선언된 캡처 키 문자열을 반환합니다. 불변 Match는 `matchedPath()`, `start()`, `end()`와 raw singleton 캡처 목록을 제공하고 비참여 그룹은 없습니다. 매칭마다 새 Matcher를 만듭니다. 통합 경로가 그 뒤 Request 뷰로 엄격 디코딩하며 helper를 직접 부르는 것만으로 디코딩하지 않습니다. 두 매처 모두 불변이며 요청 간 공유할 수 있습니다.
