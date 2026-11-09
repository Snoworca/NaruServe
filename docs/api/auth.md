# 인증 모듈 (utils.auth)

[English](en/auth.md) | [문서 목록](README.md)

> **문서 상태:** 0.11.0의 auth/RBAC v1 계약입니다. `app.use(auth)` 한 줄로 내장 로그인·refresh·로그아웃·비밀번호·계정·관리 엔드포인트, 가드, 그룹과 역할, API 키 인증이 함께 붙습니다. 이 페이지의 이름과 동작은 모두 확정입니다. **예제를 돌려 볼 때:** 이 페이지의 코드 조각은 `JWT_SECRET`을 읽고 기본 모드인 PROD로 빌드하므로, 비밀키가 없거나 약하면 거부합니다. `JWT_SECRET`에 `openssl rand -base64 32` 값을 넣거나, 로컬에서 시험할 때는 `.mode(AuthMode.DEV)`를 더하세요. 아래 빠른 시작은 이미 DEV로 돕니다.

**할 일별 안내:** 하려는 일을 찾아 그 절로 갑니다.

| 하려는 일 | 볼 절 |
|---|---|
| 로그인하고 토큰 받기 | [빠른 시작](#빠른-시작), [계정 엔드포인트](#계정-엔드포인트-authroutes) |
| 경로 보호하기(로그인 필요, 스코프 검사) | [가드와 주체](#가드와-주체), [보호 범위](#보호-범위-protectonly와-upgrade) |
| 역할과 스코프 정하기 | [역할과 스코프](#역할과-스코프-rolepolicy-rolestore-scopes) |
| 코드에서 사용자 만들기 | [앱용 사용자 파사드](#앱용-사용자-파사드-authusers), [첫 계정](#첫-계정-user) |
| 사용자 변경에 반응하기(앱 테이블 맞추기) | [AuthEvents](#authevents) |
| 그룹으로 역할 주기 | [그룹](#그룹-groupstore와-grouplimits) |
| 자체 데이터베이스에 사용자 두기 | [자체 데이터베이스 연결](#자체-데이터베이스-연결) |
| PROD 파일과 키 준비하기 | [AuthMode](#authmode), [설정 파일](#설정-파일-authconfig), [키 세트와 회전](#키-세트와-회전-keysource-jwtkeys-keysources), [파일 비밀 값 암호화](#파일-비밀-값-암호화-authfilekey) |
| LAN이나 기기에서 부르기 | [보안 연결 규칙](#보안-연결-규칙), [평문 전송 허용](#평문-전송-허용-allowinsecuretransport) |
| 테스트 쓰기 | [테스트 킷](#테스트-킷-authtesting), [HTTP 테스트 레시피](#http-테스트-레시피) |
| 관리 API 쓰기 | [관리 엔드포인트](#관리-엔드포인트-authadminroutes) |
| API 키 발급하고 쓰기 | [API 키 발급](#api-키-발급-authapikeys), [API 키 인증](#api-키-인증) |
| 낯선 용어 찾기 | [용어](#용어) |

Express에는 내장 인증이 없어서 보통 `passport`나 `express-jwt` 같은 미들웨어를 고릅니다. NaruServe는 같은 일을 하는 모듈을 `NaruServe-api` 안의 `com.snoworca.naruserve.utils.auth` 패키지로 줍니다. 새 Gradle 모듈도, 새 의존성도 없습니다. Core 공개 API만 쓰고, Java 8 바이트코드와 Android 5.0(API 21)에서 그대로 돕니다.

| 패키지 | 들어 있는 것 (지금) |
|---|---|
| `utils` | `TimeSource` |
| `utils.auth` | `Auth`, `Auth.Builder`, `AuthPrincipal`, `AccessRule`, `AuthConfig`, `AuthConfigException`, `AuthStateException`, `AuthProfile`, `Deployment`, `TokenTransport`, `KeySources`, `AuthFileKey`, `InMemoryLoginThrottle`, `InMemoryLoginThrottle.Builder`, `InMemoryRefreshTokenStore`, `FileRefreshTokenStore`, `AuthMode`, `AuthRejection`, `AuthUnavailableException`, `AuthErrorCode`, `AuthEvents`, `AuthEventContext`, `UserOrigin`, `UserChange`, `TokenService`, `TokenPair`, `Lockout`, `Lockout.After`, `PasswordPolicy`. auth가 `FileUserStore` 옆에 만드는 package-private 클래스(공개 생성자 없음, [파일 기본값](#로그인-제한-저장소-loginthrottle)): `FileLoginThrottle`, `FileDeviceTrust` |
| `utils.auth.jwt` | `Jwt`, `Jwt.Builder`, `JwtClaims`, `JwtClaims.Builder`, `JwtException`, `JwtException.Reason`, `JwtKeys`, `JwtKeys.Builder` |
| `utils.auth.password` | `PasswordHasher`, `Pbkdf2PasswordHasher`, `BcryptPasswordHasher`, `BcryptPasswordHasher.Builder`, `PasswordHashers`, `HashCost`, `DigestEncoding`, `SaltOrder` |
| `utils.auth.user` | `AuthUser`, `AuthUser.Builder`, `AuthIds`, `UserStore`, `UserPage`, `ForwardingUserStore`, `InMemoryUserStore`, `FileUserStore`, `DuplicateUserException`, `AuthUsers`, `UserMutator` |
| `utils.auth.group` | `GroupStore`, `GroupAdminStore`, `InMemoryGroupStore`, `FileGroupStore`, `GroupLimits` |
| `utils.auth.rbac` | `RolePolicy`, `RolePolicy.Builder`, `RoleStore`, `Scopes` |
| `utils.auth.spi` | `KeySource`, `AuthResponses`, `LoginThrottle`, `ForwardingLoginThrottle`, `RefreshTokenStore`, `RefreshRecord`, `RefreshRecord.Builder`, `TokenCodec`, `DeviceTrust`, `DeviceToken`, `Authenticator`, `BreachedPasswordChecker` |
| `utils.auth.audit` | `AuditEvent`, `AuditEvent.Builder`, `AuditEvent.Outcome`, `AuditSink`, `AuditSinks` |
| `utils.auth.apikey` | `ApiKeyStore`, `StoredApiKey`, `StoredApiKey.Builder`, `ApiKey`, `IssuedApiKey`, `ApiKeyStatus`, `ApiKeyPrefixCollisionException`, `AuthApiKeys`, `InMemoryApiKeyStore` |
| `utils.auth.testing` | `AuthTesting` |
| `utils.auth.internal` | auth 전용 내부 도우미. API 계약이 아니며 언제든 바뀝니다 |

## 빠른 시작

Express 대응: `app.use(cors())` → `app.use(expressjwt({ secret, algorithms: ['HS256'] }).unless({ path: ['/health'] }))` → 라우트의 `expressJwtAuthz(['posts:write'])` → 맨 끝 오류 핸들러.

아래는 그대로 실행되는 전체 프로그램입니다(의존성은 `com.snoworca.naruserve:naruserve`, [설치](../../README.ko.md#설치)).

```java
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.AuthMode;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;

public class AuthQuickStart {
    public static void main(String[] args) {
        Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
            .mode(AuthMode.DEV)                                   // 로컬 개발용: 비밀키가 없으면 임시 키. 배포는 아래 참고
            .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())
            .user("alice", "alice-password-1", "editor")          // 기동 때 없으면 만드는 계정(8자 이상: 더 짧으면 PROD는 거부, DEV는 WARN)
            .permit("/health")                                    // GET·HEAD /health만 공개, 나머지는 로그인 필요
            .build();

        RestApp.start(8080, app -> {
            app.use(auth);                                        // auth.install(app)과 같음. 라우트보다 먼저, 늦으면 IllegalStateException
            app.get("/health", (req, res) -> res.send("ok"));
            app.get("/me", (req, res) -> res.send(auth.user(req).getUsername()));
            app.post("/posts", auth.require("posts:write"), (req, res) ->
                res.status(201).send("created by " + auth.user(req).getUsername()));
            app.onError(auth.errors());                           // 맨 끝: auth 예외를 JSON으로
        });
    }
}
```

```kotlin
import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.utils.auth.Auth
import com.snoworca.naruserve.utils.auth.AuthMode
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy

fun main() {
    val auth = Auth.builder(System.getenv("JWT_SECRET"))
        .mode(AuthMode.DEV)
        .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())
        .user("alice", "alice-password-1", "editor")
        .permit("/health")
        .build()

    RestApp.start(8080) { app ->
        app.use(auth)
        app.get("/health") { _, res -> res.send("ok") }
        app.get("/me") { req, res -> res.send(auth.user(req).username) }
        app.post("/posts", auth.require("posts:write")) { req, res ->
            res.status(201).send("created by " + auth.user(req).username)
        }
        app.onError(auth.errors())
    }
}
```

실행한 뒤 다른 터미널에서 확인합니다.

```bash
curl http://localhost:8080/health                       # ok
curl -i http://localhost:8080/me                        # 401 unauthenticated
curl -X POST http://localhost:8080/auth/login -H 'Content-Type: application/json' \
     -d '{"username":"alice","password":"alice-password-1"}'
# {"accessToken":"eyJ...","tokenType":"Bearer","expiresIn":900,"refreshToken":"...",...}
curl http://localhost:8080/me -H "Authorization: Bearer eyJ..."      # alice
curl -X POST http://localhost:8080/posts -H "Authorization: Bearer eyJ..."   # 201 created by alice
```

> 로그인은 내장 `POST /auth/login`(`{"username","password"}` → `accessToken`·`refreshToken`, [계정 엔드포인트](#계정-엔드포인트-authroutes))이 합니다. 자격 증명이 없는 보호 경로 요청은 401 `unauthenticated`(`WWW-Authenticate: Bearer`), 검증되지 않는 토큰이 있는 요청은 401 `invalid_token`(`WWW-Authenticate: Bearer error="invalid_token"`)입니다. 토큰은 `Authorization: Bearer <accessToken>` 헤더로 보냅니다.

> **잘못된 URL도 401입니다:** 기본 거부는 라우터보다 먼저 돌기 때문에 어떤 라우트도 받지 않는 경로, 예를 들어 `POST /login`(로그인은 `POST /auth/login`)이나 `GET /auth/login`(로그인은 POST)도 404보다 먼저 401 `unauthenticated`를 받습니다. 그래서 잘못된 URL이 로그인을 빠뜨린 것처럼 보이니, 토큰보다 메서드와 경로를 먼저 확인하세요.

> **공개 경로:** `.permit("/health")`는 정확히 `/health`의 GET·HEAD만 공개합니다. `POST /health`, `/health/`, `/Health`는 여전히 토큰이 필요합니다(규칙이 막아 두는 메서드의 라우트가 있으면 doctor가 `permit method` WARN을 냅니다). 다른 메서드는 `.permit(Method.POST, "/health")`(`import com.snoworca.naruserve.Method;`)로, 하위 경로 전체는 `.permitPrefix("/public")`로 공개합니다.

> **로그아웃:** `POST /auth/logout`은 이 기기의 refresh 토큰을 끝내지만, 이미 발급한 액세스 토큰은 만료될 때까지(기본 15분) 유효합니다. `POST /auth/logout?all=true`는 그 사용자의 모든 기기의 토큰을 액세스 토큰까지 한 번에 끝냅니다. 클라이언트도 로그아웃할 때 액세스 토큰을 버리세요.

> **예상하지 못한 403:** `auth.require(...)`, `auth.requireRole(...)`, `.user(...)`에 스코프나 역할을 잘못 쓰면 실행 중에 403이 납니다. 기동 로그의 `auth doctor WARN undeclared scope`나 `undeclared role` 줄이 그 이름을 알려 줍니다.

**배포할 때:** `.mode(AuthMode.DEV)`를 `.mode(AuthMode.fromEnv())`로 바꾸고(`NARU_ENV=dev`일 때만 DEV) `JWT_SECRET`에 `openssl rand -base64 32` 값을 넣습니다. 계정 비밀번호는 코드에 쓰지 말고 `System.getenv("ALICE_PASSWORD")`처럼 환경에서 읽습니다. PROD는 HTTPS나 로컬 요청만 받으므로 같은 네트워크의 다른 기기에서 부르려면 [보안 연결 규칙](#보안-연결-규칙)의 LAN·기기 안내를 보세요.


## Auth 빌더

| API | 설명 | Express 대응 |
|---|---|---|
| `Auth.builder(@Nullable String secret)` | `Auth.builder(KeySources.of(secret))`의 줄임. 인자는 `System.getenv("JWT_SECRET")`처럼 String 타입 식으로 넘깁니다. Java에서 맨 `null`은 두 오버로드 사이에서 모호하므로 `Auth.builder(null)`은 컴파일 오류이고, `Auth.builder((String) null)`로 씁니다. Kotlin은 `KeySource` 오버로드가 null을 받지 않으므로 `Auth.builder(null)`을 받아들입니다 | `expressjwt({ secret })` |
| `Auth.builder(KeySource keys)` | 키 출처를 직접 줍니다 | `secret` 콜백 |
| `KeySources.of(secret)`, `fromBytes(bytes)`, `fromEnv(name)`, `fromFile(file)`, `rotating(JwtKeys)` | 문자열의 UTF-8 바이트 / 바이트 사본 / 환경 변수(지금 읽음, 없거나 비면 "설정 안 됨") / 키 파일(없으면 한 번 만듦) / kid 키 세트([키 세트와 회전](#키-세트와-회전-keysource-jwtkeys-keysources)) | `process.env.JWT_SECRET` |
| `spi.KeySource` (`@FunctionalInterface`, `byte[] signingKey() throws Exception`, default `@Nullable String signingKid()`, default `JwtKeys keys() throws Exception`) | 볼트 같은 앱 구현 | — |
| `.mode(AuthMode)` | 기본 `PROD` | `NODE_ENV` |
| `.profile(AuthProfile)` | `BASELINE`·`STANDARD`·`STRICT`. 부르지 않으면 `STANDARD`. 정책 기본값만 바꾸고 직접 준 값이 이깁니다 | — |
| `.deployment(Deployment)` | `SINGLE_INSTANCE`(기본)·`MULTI_INSTANCE` | — |
| `.transport(TokenTransport)` | `HEADER`(기본)·`COOKIE`·`BOTH` | — |
| `.users(UserStore)`, `.groups(GroupStore)`, `.groupLimits(GroupLimits)`, `.roles(RoleStore)` | 저장소. 생략하면 `InMemoryUserStore`, 그룹 없음, 깊이 8·크기 256, 내장 `RolePolicy` | passport `deserializeUser` |
| `.events(AuthEvents)` | 리스너를 더합니다(더한 순서로 실행) | `app.on(...)` |
| `.permit(path)`, `.permit(Method, path)`, `.permitPrefix(prefix)`, `.protectOnly(prefix...)` | 공개 경로. `.permit`은 디코딩하지 않은 원본 경로의 GET·HEAD 정확 일치, `.permitPrefix`는 그 경로와 `prefix + "/"` 아래 전부, `.protectOnly`는 나열한 접두사(그리고 auth 마운트와 모든 upgrade. [보호 범위](#보호-범위-protectonly와-upgrade) 참고)만 보호. 경로는 ASCII만(비ASCII는 `%XX`) | `express-jwt`의 `.unless({ path })` |
| `.permitWebSocket(path)` | 정확히 `path`로 오는 WebSocket upgrade를 토큰 없이 엽니다. 다른 upgrade는 `.permit`·`.protectOnly`와 상관없이 늘 보호됩니다 | — |
| `.refreshTokens(RefreshTokenStore)` | refresh 토큰 저장소. 부르지 않으면 사용자 저장소를 따르는 기본값([refresh 토큰 저장소](#refresh-토큰-저장소-refreshtokenstore)) | `express-session`의 세션 store |
| `.apiKeys(ApiKeyStore)` | API 키를 켭니다. 부르지 않으면 꺼짐. 켜면 사용자를 지울 때 그 키도 지우고, 10분 청소가 만료된 키를 지웁니다([API 키 저장소](#api-키-저장소-apikeystore와-키-타입)) | — |
| `.audit(AuditSink)` | 감사 이벤트를 보낼 곳. 부르지 않으면 보내지 않습니다([감사 이벤트](#감사-이벤트-auditevent-auditsink-auditsinks)) | — |
| `.cookies(Configurer<CookieOptions>)` | auth가 심는 쿠키의 공통 속성(예: `c -> c.domain("example.com")`)([auth 쿠키](#auth-쿠키)) | `res.cookie` 옵션 |
| `.mountPath(path)` | 계정 엔드포인트 마운트. 기본 `/auth`. 관리 엔드포인트는 그 아래 `/admin`(기본 `/auth/admin`)으로 따라갑니다 | `app.use("/auth", router)` |
| `.apiKeyScheme(name)` | API 키 스킴 이름. 기본 `ApiKey`. `Bearer`·`Basic`·`Digest`·`DPoP`·`Negotiate`(대소문자 무시)와 토큰 문자가 아닌 이름은 거부 | — |
| `.maxKeysPerUser(int)`, `.maxApiKeyDays(int)`, `.allowAdminScopesOnApiKeys(boolean)`, `.apiKeyFailureLimit(int max, long windowMillis)` | 사용자당 살아 있는 키 수(기본 20) / 키 엔드포인트로 발급하는 키의 최대 수명 일수(기본 365, `auth.apiKeys().issue`는 제한 없음) / 키에 관리 스코프 `auth-*` 허용(기본 false) / IP당 키 인증 실패 한도(기본 15분 900000 ms에 100회). [API 키 인증](#api-키-인증) | — |
| `.register(boolean)` | `POST /auth/register` 자가 가입. 기본 false(라우트 없음). [계정 엔드포인트](#계정-엔드포인트-authroutes) | 가입 라우트 |
| `.allowedAttributes(String... names)` | 사용자가 `PATCH /auth/me`로 스스로 바꿀 수 있는 속성 이름(예: `"displayName"`). 부르지 않으면 없음(모든 속성이 400). 여러 번 부르면 더해지고, 보안 필드 이름(`passwordHash`, `ver`, `enabled`, `roles`)은 `IllegalArgumentException` | `req.body`의 허용 목록(`_.pick`) |
| `.passwordPolicy(PasswordPolicy)`, `.breachedPasswordChecker(BreachedPasswordChecker)` | 새 비밀번호 규칙(기본 `PasswordPolicy.of(profile)`) / 유출 비밀번호 검사(기본 없음). [새 비밀번호 정책](#새-비밀번호-정책-passwordpolicy와-breachedpasswordchecker) | `express-validator` 길이 검사 |
| `.authenticator(Authenticator)` | 로그인의 비밀번호 확인을 바꿉니다(예: 디렉터리). [비밀번호 인증기](#비밀번호-인증기-spiauthenticator) | passport `LocalStrategy` verify |
| `.trustedOrigins(origin...)` | 요청 자신의 출처 말고 auth 쿠키를 받고 쓸 수 있는 출처. [Origin 검사](#origin-검사와-trustedorigins) | `cors({origin})`와 다름 |
| `.recoveryFromEnv()` | 환경 변수 `NARU_AUTH_RECOVERY`가 `1`이나 `true`면 `.recovery(true)`. 앱이 부를 때만 환경을 읽습니다 | — |
| `.statelessAccess(true)` | 가드 없는 라우트는 store를 읽지 않은 최소 주체(`getId()`만, `getUsername()`은 빈 문자열)를 봅니다. 앱은 `getId()`만 씁니다 | — |
| `.timeSource(TimeSource)`, `.logger(Logger)`, `.responses(AuthResponses)`, `.config(AuthConfig)` | 시계 / 로그(기본: install 뒤 앱 로거) / 오류 본문 / 설정 파일 | — |
| `.hashCost(HashCost)`, `.hashConcurrency(int)`, `.passwordHasher(PasswordHasher)` | 새 해시의 PBKDF2 비용(기본 `HashCost.defaults()`, 500 ms로 보정) / 동시에 도는 해시 수(기본 `max(1, 코어/2)`) / 해시기(기본 그 비용의 `Pbkdf2PasswordHasher`). [비밀번호 해시](#비밀번호-해시-passwordhasher-hashcost) | `bcrypt.hash(pw, rounds)` |
| `.user(name, @Nullable password, roles...)`, `.recovery(boolean)` | 기동할 때 없으면 만드는 계정 / 비밀번호 없는 관리자 `.user`의 일회용 setup 토큰을 엽니다(기본 false). [첫 계정](#첫-계정-user) | 시드 스크립트 |
| `.allowPreAuthMiddleware(path...)` | install 앞에 등록해도 되는 경로 미들웨어의 정확한 경로 | — |
| `.allowInsecureTransport(reason)` | PROD의 보안 연결 검사를 끕니다(TLS 어댑터가 아직 없는 기기 서버). 이유는 기동할 때마다 doctor WARN에 적힙니다. 가능하면 TLS를 쓰세요 | — |
| `build()` | 규칙을 모두 검사하고 위반을 **한꺼번에** `AuthConfigException`으로 던집니다. 부를 때마다 독립된 새 인스턴스 | — |

`AuthConfigException`(unchecked): `getViolations()`(위반마다 한 항목, 수정 불가)와 모든 항목을 담은 `getMessage()`. 메시지에 비밀값은 들어가지 않습니다. `build()`가 거부하는 것:

- PROD에서 비밀키가 없음(`JWT_SECRET is not set. Generate one with: openssl rand -base64 32 — or, for a temporary key, build with .mode(AuthMode.DEV), or with .mode(AuthMode.fromEnv()) with NARU_ENV=dev.`), 32 UTF-8 바이트 미만(실제 바이트 수와 최소값을 적음), 서로 다른 바이트 값이 8개 미만, 알려진 예제(`secret`, `changeme`, `change-me-now`, `your-256-bit-secret`, 문서 예제 상수 `naru-docs-sample-secret-do-not-use-in-production`, 앞뒤 채움 문자 포함). **DEV**에서는 비밀키가 없으면 임시 키를 만들고 "재시작하면 모든 토큰이 무효"라는 WARN을 한 번 남기며, 짧거나 예제인 키는 WARN으로 바뀝니다.
- `.permitPrefix("/")`, `.permit("")`/`.permitPrefix("")`, ASCII가 아닌 `.permit`·`.permitPrefix`·`.protectOnly` 경로.
- `MULTI_INSTANCE`에서 노드끼리 공유하지 않는 저장소: `InMemoryUserStore`, `FileUserStore`, `InMemoryGroupStore`, `FileGroupStore`, `.refreshTokens(...)`에 준 `InMemoryRefreshTokenStore`·`FileRefreshTokenStore`, 직접 준 `InMemoryLoginThrottle`·`InMemoryApiKeyStore`, 그리고 설정하지 않아 메모리 기본값이 되는 refresh 토큰·로그인 제한·기기 신뢰 저장소(그 배포에서는 앱이 만든 사용자 저장소도 메모리 refresh 저장소로 물러나지 않습니다). `MULTI_INSTANCE`는 앱이 노드끼리 공유하는 저장소(`.refreshTokens(...)`, `.loginThrottle(...)`, `.deviceTrust(...)`, 사용자·그룹 저장소)를 줄 때만 통과합니다.
- `.mountPath("/")`와 `COOKIE`·`BOTH` 전송, 잘못된 `.apiKeyScheme`, `AuthConfig`의 위반.

`RolePolicy`의 예약 `auth-*` 스코프는 이 목록에 들지 않습니다. `RolePolicy.Builder.role()`이 `Auth`를 만들기 전에 `IllegalArgumentException`으로 거부합니다.

| AuthProfile | 바꾸는 정책 기본값 |
|---|---|
| `BASELINE` | 토큰 수명 access 15분 / refresh 14일. 새 비밀번호 최소 8자(최대 128자) |
| `STANDARD` | 토큰 수명 access 15분 / refresh 1일. 새 비밀번호 최소 8자(최대 128자). `.profile(...)`을 부르지 않을 때의 프로필 |
| `STRICT` | 토큰 수명 access 5분 / refresh 12시간. 새 비밀번호 최소 15자(최대 128자). doctor가 hop 모드 프록시 신뢰를 WARN합니다 |

```java
Auth auth = Auth.builder(KeySources.fromEnv("JWT_SECRET"))
    .mode(AuthMode.fromEnv())
    .profile(AuthProfile.STRICT)          // 정책 기본값만 바꿈
    .transport(TokenTransport.BOTH)       // Bearer와 naru_at 쿠키
    .permitPrefix("/public")
    .build();                             // 위반은 모두 한 AuthConfigException으로
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .profile(AuthProfile.STRICT)
    .transport(TokenTransport.BOTH)
    .permitPrefix("/public")
    .build()
```

## install 순서

`auth.install(app)`은 앱의 등록 목록(`app.registrations()`)을 읽어, 라우트 계열(`ROUTE`, `WEBSOCKET`, `STATIC`, `ROUTER_MOUNT`, `GROUP`)이 이미 있으면 `IllegalStateException("auth.install(app) must come before routes; found GET /x registered before it")`을 던집니다. `auth.install(app)`이나 `app.use(auth)`를 두 번 부르면 `IllegalStateException("auth is already installed on this app; call app.use(auth) or auth.install(app) once, and build one Auth per app")`를 던집니다. `Auth` 하나는 앱 하나에 쓰므로 다른 앱이면 메시지가 `auth is already installed on another app; ...`입니다. 경로 없는 미들웨어(`cors`, `accessLog`, `use(Middleware)`, `use(ErrorMiddleware)`, `param`)는 앞에 와도 됩니다.

**`app.use(auth)`는 `auth.install(app)`과 같습니다.** `Auth`는 Core [`Installable`](app.md#미들웨어오류)이라 `app.use(mw)`가 미들웨어로 더하지 않고 `auth.install(app)`을 부릅니다. 순서 검사와 예외도 같습니다. Express 습관대로 쓴 `app.use("/", auth)`도 똑같이 install합니다. `app.use("/api", auth)`처럼 경로(`""`·`"/"` 말고)를 주면 등록할 때 `IllegalArgumentException`을 던집니다: `app.use("/api", mw): Auth installs itself on the whole app; call app.use(mw) without a path (to protect only a prefix with auth, build it with .protectOnly("/api"))`. `app.use("", auth)`, `router.use(auth)`(접두사 아래에 마운트한 Router도)와 라우트 미들웨어 `app.get("/x", auth, h)`는 install하지 않고 auth를 붙이므로 auth에 엔드포인트·기동 계정·점검이 없습니다. 그렇게만 붙고 install되지 않은 auth는 요청마다 `next`를 부르지 않고 500 `INTERNAL_ERROR`로 답하며, 고치는 방법(`app.use(auth)` 또는 `auth.install(app)`)을 적은 ERROR를 한 번 남깁니다. 라우트 하나만 지키려면 `auth.require(...)`를, 접두사 하나만 지키려면 `.protectOnly("/api")`로 빌드합니다. 설치되지 않았다는 ERROR는 `app.logger()`가 아니라 auth 로거(`.logger(...)`, 기본은 콘솔 로거)로 갑니다.

설치 순서는 **`cors` → `auth.install(app)` → 라우트 → 맨 끝 `app.onError(auth.errors())`**입니다. install 뒤에 `cors`를 등록하면 doctor FAIL(`cors must be registered before auth.install(app); found CORS registered after it, so auth answers preflight requests before CORS can`, DEV 포함)입니다. auth는 CORS preflight를 스스로 통과시키지 않습니다: `Origin`과 `Access-Control-Request-Method`가 있는 preflight는 앞에 등록한 CORS가 토큰 없이 답하고, `Origin`이 없는 `OPTIONS`는 다른 요청처럼 인증합니다(없으면 401). install 앞의 **경로 미들웨어**(`use("/hook", mw)`, `use(Object, Middleware)`, `useAsync(path, ...)` 중 경로가 `""`·`"/"`가 아닌 것)는 인증보다 먼저 돌아 인증 없는 엔드포인트가 될 수 있으므로 doctor FAIL(DEV 포함)입니다. `.allowPreAuthMiddleware("/hook")`로 그 경로만 허용합니다. install 앞의 미들웨어는 경로 없는 것도 doctor가 INFO 목록(`auth doctor: pre-auth middleware [...]`)으로 남깁니다.

install이 하는 일: `app.use("", auth)` 한 번(그래서 WebSocket upgrade 파이프라인에도 들어가 토큰 없는 upgrade는 101 전에 401 JSON), `auth.routes()`를 `mountPath`에, `auth.adminRoutes()`를 `mountPath + "/admin"`에 마운트(각각 마지막 층이 auth 오류 미들웨어인 라우터 안에 넣으므로, install 뒤에 더한 라우트도 JSON 오류를 받음), 등록 감시자, `onStart`의 doctor. `Auth`는 `Middleware`라 등록 목록의 첫 `MIDDLEWARE` 레코드가 `auth`입니다.

Express 대응: `app.use(cors())` → `app.use(expressjwt(...))` → 라우트 → 맨 끝 `app.use((err, req, res, next) => ...)` 오류 핸들러의 순서와 같습니다.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).build();
RestApp.start(8080, app -> {
    app.cors(c -> c.allowedOrigins("https://app.example.com"));   // 1. cors
    auth.install(app);                                            // 2. auth.install
    app.get("/me", (req, res) -> res.send(auth.user(req).getId())); // 3. 라우트
    app.onError(auth.errors());                                   // 4. 맨 끝
});
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).build()
RestApp.start(8080) { app ->
    app.cors { c -> c.allowedOrigins("https://app.example.com") }
    auth.install(app)
    app.get("/me") { req, res -> res.send(auth.user(req).id) }
    app.onError(auth.errors())
}
```

## 가드와 주체

주체에게 스코프나 역할이 없으면 가드는 403으로 답합니다. 예상하지 못한 403은 스코프나 역할 오타인 경우가 많으며, 기동 로그의 `auth doctor WARN undeclared scope`나 `undeclared role` 줄이 그 이름을 알려 줍니다.

| API | 설명 | Express 대응 |
|---|---|---|
| `auth.require(scope...)` | 모든 스코프를 가진 주체만. 아니면 403 `insufficient_scope`, 주체가 없으면 401. 스코프에 `*`가 있으면 선언할 때 `IllegalArgumentException` | `express-jwt-authz(['posts:write'])` |
| `auth.requireRole(role...)` | 나열한 역할 중 **하나라도** 가진 주체만. 아니면 403 `insufficient_scope`. API 키 요청은 403 `api_key_not_allowed`(키는 스코프를 좁힐 뿐 역할을 좁히지 않으므로) | `requireRole('editor')` 미들웨어 |
| `auth.authenticated()` | 주체를 store에서 다시 읽어 확인만. 비활성·토큰 버전 불일치 401, store 실패 503 | `passport.authenticate()` |
| `auth.check(AccessRule)` | 앱 규칙. 참이면 통과, 거짓이면 403 `insufficient_scope`, 주체 없으면 401. 규칙이 던지면 500 JSON | 손으로 짠 `(req, res, next)` 검사 |
| `AccessRule` (`@FunctionalInterface`, `boolean allows(AuthPrincipal, Request) throws Exception`) | Kotlin `auth.check { p, req -> p.hasScope("x") }` | — |
| `auth.user(req)` | 보호 경로의 주체(non-null). 주체가 없으면 `AuthStateException`(`IllegalStateException` 하위) | `req.user` |
| `@Nullable auth.userOrNull(req)` | 공개 경로에서 씁니다 | `req.user` (없으면 undefined) |
| `@Nullable auth.loadUser(req)` | 던지지 않습니다. 최소 주체를 승격하고, 사용자 없음·비활성·버전 불일치·store 실패(로그)면 null. 이미 읽은 주체면 store를 다시 부르지 않습니다 | — |
| `AuthPrincipal` | `getId()`, `getUsername()`, `getRoles()`(자기 역할 + 그룹 역할), `getScopes()`, `getAttributes()`, `isLoaded()`, `hasScope(scope)`, `hasRole(role)`, `@Nullable getApiKeyId()`(JWT 로그인이면 null). 게터는 예외를 던지지 않습니다 | `req.auth` |
| `auth.users()`, `auth.userStore()` | 앱용 사용자 파사드 / 원래 store | — |

가드는 Core `Middleware`입니다. `RouteGroup`에는 `(String, Middleware, RouteHandler)` 오버로드가 없으니 `g.route("/posts").use(auth.require("posts:read")).get(handler)`처럼 씁니다. API 키 주체의 스코프는 "키의 스코프가 덮고 **그리고** 소유자의 현재 스코프가 덮는다"로 판정합니다(와일드카드 집합 교집합이 아님).

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).build();
auth.install(app);
app.get("/admin/stats", auth.requireRole("admin"), (req, res) -> res.send("stats"));
app.patch("/users/:id", auth.check((p, r) -> p.getId().equals(r.param("id")) || p.hasScope("users:write")),
    (req, res) -> res.send("updated"));
app.group("/api", g -> g.route("/posts").use(auth.require("posts:read")).get((req, res) -> res.send("[]")));
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).build()
auth.install(app)
app.get("/admin/stats", auth.requireRole("admin")) { _, res -> res.send("stats") }
app.patch("/users/:id", auth.check { p, r -> p.id == r.param("id") || p.hasScope("users:write") }) { _, res ->
    res.send("updated")
}
```

## 보안 연결 규칙

PROD에서 auth 미들웨어는 토큰을 읽기 **전에** 연결을 봅니다. `req.isSecure()`가 참이거나 **로컬 요청**(소켓 주소가 루프백 `127.0.0.0/8`·`::1`이고 `X-Forwarded-*`·`Forwarded` 헤더가 하나도 없음)일 때만 보호 경로와 자격 증명 엔드포인트 여덟(`POST /auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/register`, `/auth/setup`, `/auth/password`, `/auth/me/apikeys`, `/auth/admin/apikeys`)을 처리하고, 아니면 403 `insecure_transport`입니다. 자격 증명 엔드포인트와 관리 엔드포인트(`/auth/admin` 아래)는 `.permitPrefix("/auth")`로도 공개되지 않습니다. 보호 범위·마운트·자격 증명 엔드포인트 판정은 기본 라우터처럼 대소문자를 무시하고 끝 슬래시·겹친 슬래시를 정리한 경로로 하고, `.permit`·`.permitPrefix`의 공개 판정은 정확히 일치할 때만이라 그 변형(예: `/HEALTH`)은 보호됩니다. 공개 경로는 이 검사를 받지 않고, 검사에 떨어지는 요청이면 토큰을 읽지 않고 익명으로 처리합니다. DEV는 이 검사를 끕니다(로컬이 아닌 첫 요청에서 WARN 한 번).

Express 대응: `app.set('trust proxy', 1)` 뒤에 `req.secure`가 거짓이면 거절하는 미들웨어.

**LAN이나 기기에서 부를 때:** 기본 TinyHttpd 엔진에는 HTTPS가 없어서, PROD 서버를 같은 네트워크의 다른 기기(휴대폰, 다른 PC)에서 평문으로 부르면 로그인과 보호 경로가 403 `insecure_transport`입니다. 그 첫 거절에 auth가 WARN을 한 번 남깁니다: `auth: POST /auth/login was refused with 403 insecure_transport: PROD serves protected paths and credential endpoints only over HTTPS or to local requests. Serve HTTPS through a TLS reverse proxy and set app.trustProxyHops(1) or app.trustProxyCidrs(...); for a LAN or device server without TLS, build with .allowInsecureTransport("reason"); for local testing, build with .mode(AuthMode.DEV). Logged once`. 방법은 셋입니다. ① TLS를 끝내는 리버스 프록시(nginx 등)를 앞에 두고 `app.trustProxyHops(1)`(또는 `app.trustProxyCidrs(...)`)을 켭니다(권장). ② TLS가 없는 LAN·기기 서버라면 빌더에 `.allowInsecureTransport("사내 LAN 데모, TLS 없음")`을 줍니다. 토큰이 평문으로 오가고 수명이 고정됩니다(access 5분, refresh 24시간). ③ 내 PC에서만 시험한다면 `.mode(AuthMode.DEV)`.

**Core의 LAN 경고:** 바인딩 주소가 루프백이 아니면(예: `app.server(o -> o.bindAddress("0.0.0.0"))`) `listen()`이 Core WARN `listening on <address>, reachable beyond this device: the app must add its own authentication`을 남깁니다(`<address>`는 바인딩 주소). Core는 auth가 있는지 보지 않으므로 auth를 설치해도 이 WARN은 나옵니다. 이 줄을 보면 두 가지를 확인합니다. ① 라우트보다 먼저 `app.use(auth)`로 auth를 설치했는지(`auth.describe()`의 `protected: ...` 줄로 보호 범위를 봅니다). ② PROD라면 위 보안 연결 규칙이 적용된다는 것: 다른 기기에서 온 평문 요청은 403 `insecure_transport`이므로 TLS 프록시나 `.allowInsecureTransport(...)` 가운데 하나를 고릅니다. 둘 다 맞으면 이 WARN은 그대로 두어도 됩니다.

**리버스 프록시 설정은 필수 배포 단계입니다.** 프록시가 `X-Forwarded-For`와 `X-Forwarded-Proto`를 붙이고, 앱은 `app.trustProxyHops(1)` 또는 `app.trustProxyCidrs("10.0.0.0/8")`로 프록시를 신뢰합니다. nginx 예:

```text
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
```

Core는 `Forwarded` 헤더를 읽지 않습니다. 그래서 `Forwarded`만 보내는 프록시의 요청은 로컬이 아니게 되지만(`req.isSecure()`는 여전히 거짓) 모든 클라이언트가 프록시 주소를 IP로 갖습니다. **남는 위험:** 같은 호스트의 프록시가 이 헤더를 하나도 붙이지 않으면 모든 요청이 로컬로 보여, 평문 외부 요청도 보안 연결 검사를 통과하고 모든 클라이언트가 프록시 주소를 IP로 공유해 로그인 IP 한도가 모든 사용자에게 한 키로 걸립니다(`/auth/setup`은 일회용 setup 토큰으로 따로 보호됩니다).

프록시 신뢰를 설정하지 않았는데 `X-Forwarded-For`가 붙은 요청이 처음 오면 "모든 사용자가 LB IP를 공유하니 `trustProxyHops`를 설정하라"는 WARN을 한 번 남깁니다. `SINGLE_INSTANCE`에서 프록시 헤더가 붙은 요청이 처음 오면 "여러 노드라면 `deployment(MULTI_INSTANCE)`와 공유 저장소를 쓰라"는 WARN을 한 번 남깁니다. 선언하지 않은 다중 노드 운영은 일부만 탐지됩니다. 탐지 수단은 둘입니다. `SINGLE_INSTANCE`에서 프록시 헤더가 붙은 첫 요청에 남기는 위의 WARN 한 번, 그리고 이 노드의 인메모리 refresh 저장소에 기록이 없는 refresh 토큰이 올 때 401 `invalid_refresh_token`과 함께 남기는 WARN 한 번(재시작 전에 발급되었거나 다른 노드가 발급한 토큰, 토큰 값은 남기지 않음)입니다. 완전한 탐지는 불가능합니다.

## 오류 응답과 AuthResponses

auth 미들웨어, 가드, auth 라우터가 내는 응답은 모두 JSON이고, PROD에서는 기동 전에 등록한 앱 라우트가 던진 예외의 응답도 모두 JSON입니다(아래 참고. auth 마운트 밖에서 어떤 라우트도 받지 않는 경로는 인증을 통과한 뒤 여전히 Core의 404입니다). 기본 본문은 Core 모양 `{"error":{"code":…,"message":…,"requestId":…}}`이고 `requestId`는 그 요청의 `req.requestId()`입니다. 본문에는 스택 트레이스와 예외 클래스 이름이 들어가지 않습니다. 메시지는 4xx `HttpException`(`AuthRejection` 포함)의 것만 보이고, 그 밖의 예외 메시지는 넣지 않습니다. 미들웨어나 가드의 store 실패는 503 `SERVICE_UNAVAILABLE`입니다.

auth는 오류 레이어를 빠뜨려도 자기 예외를 JSON으로 답합니다. 기동할 때 라우트 뒤에 `auth.errors()`나 `auth.errorMiddleware()`가 없으면 auth가 라우트 뒤에 `auth.errorMiddleware()`를 덧붙입니다(doctor가 `errors placement` WARN을 냅니다). 그래서 Java 스택 트레이스가 든 Core의 HTML 오류 페이지는 `auth.errorMiddleware()` 없이 기동 뒤에 더한 핸들러에서만 나옵니다. PROD에서는 기동할 때 그때까지 등록된 모든 층 뒤에(앱의 오류 층이 먼저 답합니다) 나머지 모든 예외를 받는 마지막 오류 층도 덧붙입니다. Core `HttpException`은 그 상태와, 500 미만이면 그 메시지를 유지하고(예: `?page=abc`에 `req.queryInt("page", 1)`은 400 `page must be a 32-bit integer`), 앱의 `IllegalStateException` 같은 나머지는 메시지·예외 클래스·스택 트레이스 없이 500 `INTERNAL_ERROR`입니다(500은 Core가 로그에 남깁니다). DEV는 이 층을 붙이지 않아 개발 중에는 스택 페이지를 그대로 볼 수 있습니다. 토큰 없이 auth 엔드포인트를 다른 메서드로 부르면 기본 거부가 라우팅보다 먼저 돌아 401 `unauthenticated`이고, 메시지가 메서드를 알려 줍니다. 예를 들어 `GET /auth/logout`은 `authentication is required; this endpoint takes POST /auth/logout`입니다. 토큰이 있으면 라우팅까지 가고, auth 마운트 아래에서 어떤 라우트도 받지 않은 요청은 auth가 기동할 때 덧붙이는 마지막 층이 JSON으로 답합니다. 그 경로의 라우트가 다른 메서드를 받으면 405 `METHOD_NOT_ALLOWED`와 `Allow` 헤더이고(`GET /auth/logout`은 `this endpoint takes POST /auth/logout`, `PUT /auth/admin/users/1`은 `this endpoint takes GET, PATCH, DELETE /auth/admin/users/:id`), 아니면 404 `NOT_FOUND` `no auth endpoint serves <method> <path>`입니다. `.apiKeys(...)` 없이 키 경로를 부르면 `API keys are off: build auth with .apiKeys(store)`가, `/auth/admin/groups/:id/roles`에는 `grantRole`이 붙습니다(0.11.0에서 그룹 역할은 코드로 줍니다). 기동 뒤에 앱이 auth 마운트 아래에 더한 라우트는 그 층 뒤에 놓여 닿지 않습니다. `.register(true)` 없이 부른 `POST /auth/register`는 앱이 그 경로를 직접 받지 않는 한 auth JSON의 404 `NOT_FOUND`이고 메시지는 `sign-up is off: POST /auth/register is served only when auth is built with .register(true)`입니다. 토큰 없는 refresh는 401 `invalid_refresh_token`과 `no refresh token: send it as the "refreshToken" field of a JSON body`(COOKIE는 `no refresh token: COOKIE mode reads it from the naru_rt cookie set at login`)이고, auth 라우터에서 해석되지 않거나 JSON 배열·문자열인 본문은 400 `BAD_REQUEST`와 `the request body must be a JSON object`입니다. 본문 없는 `POST /auth/login`은 415와 `auth endpoints accept only application/json bodies`입니다. 빈 배열 `[]`은 Gson 코덱이 빈 객체로 읽으므로 그 엔드포인트의 필드 누락 응답(예: `username must be a JSON string`)을 받습니다.

앱 핸들러가 던진 auth 예외는 라우트 뒤에 둔 `auth.errors()`·`auth.errorMiddleware()`가 같은 JSON으로 바꿉니다([auth 오류 레이어](#auth-오류-레이어-autherrors-autherrormiddleware)). 401 응답에는 늘 `WWW-Authenticate: Bearer`가 붙고, 읽은 토큰이 검증되지 않은 401(`invalid_token`)이면 `Bearer error="invalid_token"`입니다.

auth 라우터의 오류 미들웨어: 잘못된 JSON 400 `BAD_REQUEST`, 너무 큰 본문 413 `PAYLOAD_TOO_LARGE`, JSON이 아닌 본문 415 `UNSUPPORTED_MEDIA_TYPE`(Core `HttpException` 상태), `AuthRejection`은 그 상태·코드(+ `Retry-After`), `DuplicateUserException` 409 `duplicate_user`, `AuthUnavailableException` 503과 그 코드, 그 밖의 store 예외(`SQLException`, `IOException`) 503 `SERVICE_UNAVAILABLE`, 나머지 500 `INTERNAL_ERROR`. 503과 500은 로그를 남기고, 4xx(`AuthRejection`, `DuplicateUserException`, Core `HttpException`)는 남기지 않습니다.

`spi.AuthResponses`(`@FunctionalInterface`, `void error(Request req, Response res, int status, String code, String message) throws Exception`)를 `.responses(...)`로 주면 본문 모양만 바뀌고 상태는 그대로입니다(상태와 `Retry-After`는 auth가 먼저 정합니다). 구현이 던지면 로그를 남기고 기본 JSON 본문으로 답합니다. Express 대응: 마지막에 두는 오류 핸들러 `(err, req, res, next) => res.status(...).json(...)`.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .responses((rq, rs, status, code, message) -> rs.type("application/json").send("{\"err\":\"" + code + "\"}"))
    .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .responses { _, res, _, code, _ -> res.type("application/json").send("{\"err\":\"$code\"}") }
    .build()
```

## 설정 파일: AuthConfig

운영 값은 `java.util.Properties` 파일로 넣습니다: `Auth.builder((String) null).config(AuthConfig.load(new File("auth.properties")))`. `.config(...)`는 그 자리에서 setter를 부른 것처럼 적용하므로 뒤의 setter가 이깁니다. 객체(store·sink)는 코드로만 넣고, 클래스 이름을 받는 키는 없습니다. 모르는 키, 틀린 값, 풀리지 않는 비밀 참조는 빌더의 위반과 함께 `build()`가 한꺼번에 보고합니다. Express 대응: `dotenv`.

| 키 | 값 |
|---|---|
| `naru.auth.secret` | 참조만: `env:NAME`(환경 변수, `KeySources.fromEnv`) 또는 `file:/path`(파일 내용, 앞뒤 공백 제거). 비밀값을 파일에 직접 쓰면 위반 |
| `naru.auth.mode` | `dev`·`prod` |
| `naru.auth.profile` | `baseline`·`standard`·`strict` |
| `naru.auth.deployment` | `single-instance`·`multi-instance` |
| `naru.auth.transport` | `header`·`cookie`·`both` |
| `naru.auth.mount-path` | 예: `/auth` |
| `naru.auth.permit`, `naru.auth.permit-prefix`, `naru.auth.protect-only` | 쉼표로 나눈 경로 |
| `naru.auth.stateless-access` | `true`·`false` |
| `naru.auth.api-key-scheme` | 스킴 이름 |

`AuthConfig.from(Properties)`도 있습니다. `AuthConfig.KEYS`가 알려진 키 목록입니다.

```java
Properties p = new Properties();
p.setProperty("naru.auth.secret", "env:JWT_SECRET");   // 비밀은 참조로만
p.setProperty("naru.auth.profile", "strict");
Auth auth = Auth.builder((String) null).config(AuthConfig.from(p)).permit("/health").build();
```

```kotlin
val props = java.util.Properties()
props.setProperty("naru.auth.profile", "strict")
val auth = Auth.builder(System.getenv("JWT_SECRET")).config(AuthConfig.from(props)).build()
println(AuthConfig.KEYS)
```

## 시작 점검(doctor)

`onStart`에서 돌고, 통과하면 이어서 `.user(...)` 계정을 만듭니다. 항목마다 FAIL이나 WARN이고, FAIL이 하나라도 있으면 포트를 열기 전에 기동이 멈추며 예외 메시지가 FAIL을 모두 적습니다. WARN은 한 번씩 로그에 남고 기동은 계속됩니다. 주요 항목: store 연결(`findByUsername("__naru_probe__")`이 던지면 FAIL), 공개 경로 목록(INFO 목록), install 앞의 미들웨어 목록(INFO)과 허용하지 않은 경로 미들웨어 FAIL, 이 기기에서 잰 해시 비용(INFO `auth doctor: hash cost N PBKDF2 iterations, X ms per hash ...`), 프록시 신뢰(PROD의 `trustProxy(true)` FAIL, `MULTI_INSTANCE`인데 프록시 신뢰 없음 WARN, `MULTI_INSTANCE`·`STRICT`의 hop 모드 WARN — CIDR 모드 권장), install 뒤의 `cors` FAIL, `SINGLE_INSTANCE`에서 앱이 만든 `UserStore`(내장 store가 아닌 것)를 쓰면 "재시작하면 모든 사용자가 로그아웃된다" WARN 한 번, 마지막 라우트 뒤에 `auth.errors()`·`auth.errorMiddleware()`가 없으면 WARN(errors placement), `MULTI_INSTANCE`에서 `supportsAtomicFieldWrites()`가 false인 `UserStore` FAIL, `HashCost.fixed(n)`이 50,000회 미만이면 PROD FAIL·DEV WARN, 비밀번호 없는 `.user` 규칙 FAIL, `undeclared role`(`.user(...)`나 `auth.requireRole(...)`이 쓰는데 `RolePolicy`가 선언하지 않은 역할마다 WARN 하나), `undeclared scope`(`auth.require(...)`가 요구하는데 어떤 `RolePolicy` 역할도 주지 않는 스코프마다 WARN 하나: `scope '<s>' is required by auth.require(...) but no RolePolicy role grants it (admin's * aside); a typo makes the scope check fail with 403. Grant it with RolePolicy.builder().role("<role>", "<s>")`. 두 WARN은 역할이 앱의 `RoleStore`가 아니라 `RolePolicy`에서 올 때만), `.allowInsecureTransport` WARN. 그 밖의 항목은 해당 기능의 절에 적습니다.

다음 네 항목도 있습니다: `registration`(PROD에서 `.register(true)`로 누구나 가입할 수 있으면 WARN), `clock`(`TimeSource`의 현재 시각이 모듈 빌드 날짜의 하한 2025-01-01보다 이르면 WARN. 배터리가 빠진 기기는 2000년이나 1970년으로 돌아갑니다), `login throttle limits`(`.apiKeys(...)`를 켰는데 `LoginThrottle`의 `usesLimitArguments()`가 false면 `.apiKeyFailureLimit` 값이 쓰이지 않으므로 WARN), `file state`(`FileUserStore`·`FileGroupStore`·`FileRefreshTokenStore` 파일과 `FileUserStore` 옆의 로그인 제한·기기 신뢰 파일의 디렉터리에 쓸 수 없으면 FAIL, 그 옆에 `<이름>.tmp`가 남아 있으면 이름 바꾸기 전에 멈춘 쓰기라 WARN). `FileUserStore`나 `KeySources.fromFile`을 쓰면 `plain secret values in auth files`(평문으로 남은 비밀 값 수, 0보다 크면 WARN)도 있습니다([파일 비밀 값 암호화](#파일-비밀-값-암호화-authfilekey)).

## AuthMode

| API | 설명 | Express 대응 |
|---|---|---|
| `AuthMode.PROD` | 기본값 | `NODE_ENV=production` |
| `AuthMode.DEV` | 정해진 여덟 가지만 완화합니다: 비밀키가 없을 때 임시 키, 보안 연결 검사, 해시 비용·키 강도 실패를 WARN으로, 비밀번호 정책을 어긴 `.user(...)` 비밀번호(WARN과 함께 계정 생성), 비밀번호 없는 관리자 `.user(...)`(임시 비밀번호를 로그에 남김), 파일 키 없는 파일 저장소(WARN과 함께 평문 저장), 옛 `trustProxy(true)`(doctor FAIL 없음), 마지막 auth 오류 레이어 없음(앱 예외가 Core로 넘어감). 권한 검사는 절대 완화하지 않습니다 | `NODE_ENV=development` |

모드는 코드로 넘깁니다. `AuthMode.fromEnv()`는 환경 변수 `NARU_ENV`가 `dev`(대소문자 무시)면 `DEV`, 없거나 비었거나 `prod`이거나 그 밖의 값이면 `PROD`입니다. 환경을 읽는 것은 앱이 직접 하는 이 호출뿐이고, 빌더는 스스로 환경을 읽지 않습니다. `.mode(...)`를 부르지 않은 빌더는 `PROD`입니다.

| DEV가 완화하는 것(전부) | PROD |
|---|---|
| 비밀키가 없으면 임시 키(재시작하면 모든 토큰 무효, WARN) | `build()`가 `AuthConfigException` |
| 보안 연결 검사(평문·비로컬 요청도 처리, 로컬이 아닌 첫 요청에서 WARN 한 번) | 403 `insecure_transport` |
| 짧거나 예제인 키, 해시 비용 실패를 WARN으로 | `build()` 거부 / doctor FAIL |
| 비밀번호 정책을 어긴 `.user(...)` 비밀번호를 WARN으로 하고 계정을 만듦 | `build()`가 `AuthConfigException` |
| 비밀번호 없는 관리자 `.user(...)`에 임시 비밀번호를 만들어 로그에 남김 | doctor FAIL(`.recovery(true)`로 시작하면 일회용 설정 토큰) |
| 파일 키 없는 파일 저장소를 WARN으로 하고 평문으로 저장 | `build()`가 `AuthConfigException` |
| 옛 `trustProxy(true)`의 doctor FAIL 없음 | doctor FAIL |
| 마지막 auth 오류 레이어가 없음(앞선 레이어가 답하지 않은 앱 예외는 Core 페이지와 스택 트레이스로) | 그 예외도 Core 모양의 JSON |

기본 거부, install 순서, 공개 경로 규칙, 권한 검사, auth 자신의 JSON 오류 본문은 DEV에서도 PROD와 똑같습니다.

```java
AuthMode mode = AuthMode.fromEnv();                 // NARU_ENV=dev면 DEV, 그 밖은 PROD
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).mode(mode).build();
System.out.println(auth.mode());
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).mode(AuthMode.fromEnv()).build()
if (auth.mode() == AuthMode.DEV) println("dev relaxations on")
```

## 오류: AuthRejection, AuthUnavailableException, AuthErrorCode

auth가 만드는 오류 응답은 모두 Core와 같은 JSON 모양입니다: `{"error":{"code":…,"message":…,"requestId":…}}`.

- `new AuthRejection(int status, String code)`, `(status, code, @Nullable String message)`, `(status, code, message, @Nullable Long retryAfterSeconds)` — Core `HttpException`을 상속한 unchecked 예외라(`status()`가 그 상태) Core는 이것을 클라이언트 오류로 보고 로그를 남기지 않으며, auth 오류 레이어가 없어도 그 상태로 답합니다. `DuplicateUserException`도 상태 409인 `HttpException`입니다. `status`는 400–499, `code`는 `[a-z][a-z0-9_]{0,63}`만 받고 그 밖에는 `IllegalArgumentException`입니다. 메시지가 null이면 상태의 기본 문구(예: `Unprocessable Content`)로 답하고, `retryAfterSeconds`가 있으면 `Retry-After` 헤더를 붙입니다. `getStatus()`, `getCode()`, `@Nullable getRetryAfterSeconds()`.
- `new AuthUnavailableException(@Nullable String message)`, `(message, @Nullable Throwable cause)`, `(String code, message, cause)`, `(code, message, @Nullable Long retryAfterSeconds, cause)` — store·리스너·해시 슬롯 실패를 뜻하는 503입니다. 해시 슬롯이 모자라면 `Retry-After` 1초를 싣습니다. 코드를 주지 않으면 Core 표기 `SERVICE_UNAVAILABLE`, 확인되지 않은 보안 쓰기는 `security_write_conflict`입니다. `getCode()`, `@Nullable getRetryAfterSeconds()`, `getStatus()`(항상 503).
- `AuthErrorCode` — auth가 쓰는 코드와 상태의 고정 목록입니다(`getCode()`, `getStatus()`). 400 `BAD_REQUEST`, 404 `NOT_FOUND`, 415 `UNSUPPORTED_MEDIA_TYPE`, 503 `SERVICE_UNAVAILABLE`은 Core 코드라 이 목록에 없습니다. 입력 검사의 400은 Core `HttpException`으로 내고, JSON이 아닌 auth 본문의 415는 전역 auth 미들웨어가 같은 JSON 모양(코드 `UNSUPPORTED_MEDIA_TYPE`)으로 직접 답하며(그래서 앱의 `onError`에는 가지 않고 `AuthResponses`가 모양을 정합니다), 503 `SERVICE_UNAVAILABLE`은 `AuthUnavailableException`의 기본 코드입니다.

| code | 상태 | 쓰는 답 |
|---|---|---|
| `unauthenticated` | 401 | 보호 경로에 자격 증명이 없음. 메시지는 `authentication is required`이고, `Authorization` 헤더 하나가 Bearer가 아닌 scheme(예: `Token abc`, scheme 없는 토큰)이면 `authentication is required; send the access token as Authorization: Bearer <token>`입니다(HEADER·BOTH. 키를 켰으면 API 키 scheme은 키로 판정) |
| `invalid_token` | 401 | 검증되지 않거나 만료된 Bearer 토큰 |
| `invalid_api_key` | 401 | 검증되지 않는 API 키 |
| `invalid_credentials` | 401 | 틀린 아이디·비밀번호. **현재 비밀번호가 틀린 경우도 이 코드입니다**(`AuthErrorCode.WRONG_CURRENT_PASSWORD`는 이 상수의 별칭) |
| `invalid_refresh_token` | 401 | 검증되지 않거나 철회·재사용된 refresh 토큰 |
| `invalid_setup_token` | 401 | 로그에 찍힌 setup 토큰과 다른 값 |
| `invalid_request` | 400 | 한 요청에 자격 증명이 둘 이상(보호 경로와 자격 증명 엔드포인트에서만) |
| `service_account_no_password` | 400 | 서비스 계정에 비밀번호 작업 |
| `username_mismatch` | 400 | setup 본문의 이름이 설정한 이름과 다름 |
| `insufficient_scope` | 403 | 스코프 부족. `auth.requireRole`의 거부, 거짓인 `auth.check` 규칙, 자기 자신에 대한 부여 규칙 거부도 이 코드입니다 |
| `api_key_not_allowed` | 403 | API 키로 부를 수 없는 엔드포인트 |
| `insecure_transport` | 403 | PROD에서 평문·비로컬 요청 |
| `csrf_failed` | 403 | 쿠키 모드의 CSRF 토큰·Origin 실패 |
| `setup_not_local` | 403 | 로컬이 아닌 setup 요청 |
| `api_key_limit` | 409 | 사용자당 API 키 한도 |
| `duplicate_user` | 409 | 생성·이름 변경의 중복 이름 |
| `write_conflict` | 409 | 동시 쓰기에 진 비밀번호 변경 |
| `group_cycle` | 409 | 순환이나 깊이 한도를 넘기는 그룹 간선 |
| `group_too_large` | 409 | 사용자 1,000명을 넘게 건드리는 그룹 삭제 |
| `too_many_attempts` | 429 | 로그인·비밀번호 확인·API 키 실패 한도 |
| `security_write_conflict` | 503 | 다시 읽어도 확인되지 않은 보안 쓰기 |
| `SERVICE_UNAVAILABLE` | 503 | store·throttle·해시 슬롯·그룹 확장 한도 실패(Core 표기, `AuthUnavailableException` 기본값) |

Express 대응: `http-errors`의 `throw createError(422, ...)`를 맨 끝 오류 핸들러가 JSON으로 바꾸는 것.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).build();
auth.install(app);
app.post("/orders", (req, res) -> {
    if (req.header("X-Tenant") == null) throw new AuthRejection(422, "tenant_required", "Send X-Tenant");
    res.status(201).send("ok");
});
app.onError(auth.errors());   // AuthRejection → 422 {"error":{"code":"tenant_required",...}}
```

```kotlin
app.post("/orders") { req, res ->
    if (req.header("X-Tenant") == null) throw AuthRejection(422, "tenant_required", "Send X-Tenant")
    res.status(201).send("ok")
}
println(AuthErrorCode.INVALID_TOKEN.status)
```

## AuthEvents

모든 메서드가 default no-op인 리스너입니다. 필요한 것만 재정의합니다(Java `new AuthEvents() { … }`, Kotlin `object : AuthEvents { … }`). Express의 `app.on('event', …)`와 비슷하지만 거부할 수 있는 이벤트가 있습니다.

- 거부 가능(동기, store 쓰기 전): `beforeLogin(user, ctx)`, `beforeUserCreate(candidate, ctx)`, `beforeUserUpdate(before, after, ctx)`, `beforeUserDelete(user, ctx)`. `AuthRejection`을 던지면 그 상태와 코드로 거부합니다. 그 밖의 예외(`HttpException` 포함)는 로그를 남기고 503 `AuthUnavailableException`이 됩니다.
- 알림(쓰기 성공 뒤, 응답 전): `onLogin`, `onUserCreated`, `onUserUpdated(before, after, changes, ctx)`, `onPasswordChanged`, `onUserDeleted`, `onTokenRefreshed`, `onLogout(userId, allDevices, ctx)`, `onRefreshTokenReused(userId, familyId, ctx)`(재사용한 refresh 토큰의 계열을 폐기한 뒤). 실패는 로그만 남고 응답은 그대로입니다.
- 콜백이 받는 `AuthUser`는 모두 `passwordHash`가 null인 사본입니다.
- `AuthEventContext`: `@Nullable getOrigin()`(`UserOrigin`: `REGISTER`, `ADMIN`, `APP`, `SELF`, `SETUP`, `RECOVERY`, `BOOTSTRAP` — 사용자 변경 이벤트에서만 값이 있음), `@Nullable getRequest()`, `@Nullable getActorId()`, `getTimestamp()`(epoch 밀리초). `UserChange`: `USERNAME`, `ROLES`, `ENABLED`, `PASSWORD`, `ATTRIBUTES`.

모든 메서드는 `default void … throws Exception`입니다. 아래 표의 시그니처에서는 그 부분을 뺐습니다.

| 메서드와 Java 시그니처 | 언제 부르나 | 거부 |
|---|---|---|
| `beforeLogin(AuthUser user, AuthEventContext ctx)` | 비밀번호가 맞은 로그인을 마치기 전 | 예 |
| `onLogin(AuthUser user, AuthEventContext ctx)` | 로그인이 성공한 뒤 | 아니요 |
| `beforeUserCreate(AuthUser candidate, AuthEventContext ctx)` | 가입, 관리자 생성, `auth.users().create` 전. `candidate`에는 아직 id가 없습니다 | 예 |
| `onUserCreated(AuthUser user, AuthEventContext ctx)` | 사용자를 만든 뒤(가입, 관리자, 앱, setup, 복구, `.user` 시드) | 아니요 |
| `beforeUserUpdate(AuthUser before, AuthUser after, AuthEventContext ctx)` | 이름 변경, 속성 변경, 관리자 수정, 비밀번호 변경 전. 바뀌기 전과 뒤 값을 받습니다 | 예 |
| `onUserUpdated(AuthUser before, AuthUser after, Set<UserChange> changes, AuthEventContext ctx)` | 사용자를 바꾼 뒤. `changes`가 바뀐 것입니다 | 아니요 |
| `onPasswordChanged(AuthUser user, AuthEventContext ctx)` | 비밀번호를 바꾸거나 재설정한 뒤 | 아니요 |
| `beforeUserDelete(AuthUser user, AuthEventContext ctx)` | 관리자 삭제, `auth.users().delete` 전 | 예 |
| `onUserDeleted(AuthUser user, AuthEventContext ctx)` | 사용자를 지운 뒤 | 아니요 |
| `onTokenRefreshed(AuthUser user, AuthEventContext ctx)` | refresh 토큰을 새 토큰으로 바꾼 뒤(`POST /auth/refresh`) | 아니요 |
| `onRefreshTokenReused(String userId, String familyId, AuthEventContext ctx)` | 이미 쓴 refresh 토큰이 유예 시간 밖에 다시 와서 그 계열(family)을 폐기한 뒤. 클라이언트는 보통의 refresh 실패 응답만 받으므로 감지는 이 이벤트로만 압니다 | 아니요 |
| `onLogout(String userId, boolean allDevices, AuthEventContext ctx)` | 로그아웃한 뒤. `?all=true`면 `allDevices`가 true입니다 | 아니요 |

거부가 "예"인 이벤트는 `AuthRejection`을 던져 그 일을 막습니다. "아니요"인 이벤트에서 던진 예외는 로그만 남깁니다. 리스너는 빌더의 `.events(listener)`로 붙입니다.

Express 대응: `app.on('event', …)` 리스너. 다만 `before*`는 던져서 거부합니다.

```java
AuthEvents events = new AuthEvents() {
    @Override
    public void beforeUserCreate(AuthUser candidate, AuthEventContext ctx) {
        if (ctx.getOrigin() == UserOrigin.REGISTER && !candidate.getUsername().endsWith("@example.com")) {
            throw new AuthRejection(422, "domain_not_allowed", "Use your company address");
        }
    }
};
```

```kotlin
val events = object : AuthEvents {
    override fun beforeUserCreate(candidate: AuthUser, ctx: AuthEventContext) {
        if (ctx.origin == UserOrigin.REGISTER && !candidate.username.endsWith("@example.com")) {
            throw AuthRejection(422, "domain_not_allowed", "Use your company address")
        }
    }
}
```

이름·속성 정책을 `beforeUserCreate`에 두었다면 `beforeUserUpdate`에도 두어야 이름 변경으로 우회되지 않습니다.

**앱 테이블 맞추기:** 앱이 자기 테이블(프로필, 통계 등)에 사용자 정보를 따로 둔다면 `on*` 이벤트로 맞춥니다. 아래 예는 만들기·바꾸기·지우기·로그인마다 앱의 맵을 고칩니다. 만든 뒤의 사용자는 항상 id가 있습니다. 이 이벤트는 쓰기가 성공한 뒤에 오고 실패는 로그만 남기므로, 앱 테이블 쓰기가 실패해도 사용자 쓰기는 되돌아가지 않습니다.

```java
Map<String, String> profiles = new ConcurrentHashMap<>();   // 앱 테이블: 사용자 id → 이름
Map<String, Long> lastLogin = new ConcurrentHashMap<>();    // 사용자 id → 마지막 로그인 시각
AuthEvents sync = new AuthEvents() {
    @Override
    public void onUserCreated(AuthUser user, AuthEventContext ctx) {
        profiles.put(user.getId(), user.getUsername());
    }

    @Override
    public void onUserUpdated(AuthUser before, AuthUser after, Set<UserChange> changes, AuthEventContext ctx) {
        if (changes.contains(UserChange.USERNAME)) {
            profiles.put(after.getId(), after.getUsername());
        }
    }

    @Override
    public void onUserDeleted(AuthUser user, AuthEventContext ctx) {
        profiles.remove(user.getId());
        lastLogin.remove(user.getId());
    }

    @Override
    public void onLogin(AuthUser user, AuthEventContext ctx) {
        lastLogin.put(user.getId(), ctx.getTimestamp());
    }
};
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).events(sync).build();
```

```kotlin
val profiles = ConcurrentHashMap<String, String>()   // 앱 테이블: 사용자 id → 이름
val lastLogin = ConcurrentHashMap<String, Long>()    // 사용자 id → 마지막 로그인 시각
val sync = object : AuthEvents {
    override fun onUserCreated(user: AuthUser, ctx: AuthEventContext) {
        profiles[user.id!!] = user.username
    }

    override fun onUserUpdated(before: AuthUser, after: AuthUser, changes: Set<UserChange>, ctx: AuthEventContext) {
        if (UserChange.USERNAME in changes) profiles[after.id!!] = after.username
    }

    override fun onUserDeleted(user: AuthUser, ctx: AuthEventContext) {
        profiles.remove(user.id!!)
        lastLogin.remove(user.id!!)
    }

    override fun onLogin(user: AuthUser, ctx: AuthEventContext) {
        lastLogin[user.id!!] = ctx.timestamp
    }
}
val auth = Auth.builder(System.getenv("JWT_SECRET")).events(sync).build()
```

## AuthUser와 AuthIds

`AuthUser`는 불변 값 클래스입니다. 어노테이션 매핑이 아니라 store 어댑터의 `toAuthUser(row)` 한 곳에서 행을 바꿉니다(Android 릴리스 빌드의 코드 축소 도구가 어노테이션을 지웁니다).

| API | 설명 |
|---|---|
| `AuthUser.builder()` / `toBuilder()` | 만들기 / 바꾼 사본 만들기. `build()`는 username이 없으면 `IllegalStateException` |
| `@Nullable getId()` | store가 정한 String id. create 전에만 null. 삭제된 id는 재사용하지 않습니다 |
| `getUsername()` | 정규화된 로그인 이름 |
| `@Nullable getPasswordHash()` | 비밀번호 해시. 비밀번호 로그인이 없는 사용자는 null |
| `getTokenVersion()` | 토큰 버전. 올리면 그 전에 발급한 토큰이 모두 무효 |
| `isEnabled()`, `isServiceAccount()` | 활성 여부 / 서비스 계정(비밀번호 없이 API 키로만 인증, 만들 때 정하고 바꾸지 않음) |
| `getRoles()`, `getAttributes()`, `@Nullable getAttribute(name)` | 역할과 앱 속성. 돌려주는 컬렉션은 수정할 수 없습니다 |
| `AuthIds.parseLong(id)`, `AuthIds.parseUuid(id)` | 숫자·UUID 키로 바꾸기. `"7"` → 7, 소문자 표준 UUID → UUID. `"abc"`, `"007"`, `"+7"`, `" 7"`은 null(그래서 `findById`가 500이 아니라 404) |

v1 `AuthUser`에는 직접 스코프가 없습니다. 스코프는 역할로만 줍니다. `toString()`은 해시와 속성 값을 보이지 않습니다(속성은 이름만).

`passwordHash`는 `transient`라서 기본 modifier 설정의 Gson(`new GsonJsonCodec()`)만 이 값을 빼고 씁니다. `excludeFieldsWithModifiers(STATIC)`로 만든 Gson이나 다른 `JsonCodec`을 쓰면 앱이 `passwordHash`를 직접 지워야 합니다.

Express 대응: Mongoose·Sequelize의 사용자 모델 객체와 `parseInt(req.params.id, 10)` 검사.

```java
AuthUser u = AuthUser.builder().username("alice").roles("editor").attribute("team", "web").build();
AuthUser moved = u.toBuilder().attribute("team", "api").build();
Long numeric = AuthIds.parseLong("42");        // 42. "007"이면 null
UUID uuid = AuthIds.parseUuid("not-a-uuid");   // null
```

```kotlin
val u = AuthUser.builder().username("alice").roles("editor").attribute("team", "web").build()
val team: String? = u.toBuilder().attribute("team", "api").build().getAttribute("team")
val numeric: Long? = AuthIds.parseLong("42")
```

## 사용자 저장소: UserStore

`UserStore`는 앱이 자기 테이블 위에 구현합니다. 필수 메서드는 다섯 개이고 모두 동기 `throws Exception`입니다. 예외는 auth가 503으로 답합니다. Express 대응: passport의 `deserializeUser`와 사용자 모델.

| 메서드 | 설명 |
|---|---|
| `@Nullable findByUsername(String username)` | 정규화된 이름(NFKC, `Locale.ROOT` 소문자)이 옵니다. 이름·이메일·사번 중 무엇으로 찾을지는 store가 정합니다 |
| `@Nullable findById(String id)`, `create(AuthUser)`, `@Nullable update(AuthUser)`, `boolean delete(String id)` | create는 id를 정해 저장한 사용자를 돌려주고, update는 행 전체를 바꾸며(없으면 null), delete는 없으면 false |
| default `updatePasswordHash(id, expectedOldHash, newHash)` | 재해시. 확인되지 않으면 조용히 건너뜁니다(false) |
| default `changePassword(id, expectedOldHash, expectedVer, newHash)` | 해시 교체와 `ver + 1`을 한 번에 |
| default `setEnabled`, `bumpTokenVersion`, `setRoles` | 보안 필드 쓰기. 다시 읽어 확인되지 않으면 세 번 더 시도한 뒤 503 `security_write_conflict`(`changePassword`는 한 번 뒤) |
| default `UserPage list(int offset, int limit, @Nullable String search)` | 관리 목록. 기본은 `UnsupportedOperationException`. `UserPage`: `getItems()`, `getTotal()`(모르면 -1), `getOffset()`, `getLimit()` |
| default `supportsAtomicFieldWrites()` | 다섯 필드 메서드를 원자적으로 구현했으면 true. 기본 false |

store가 지키는 계약: id는 String이고 삭제된 id는 재사용하지 않습니다. `ver`는 늘기만 합니다. create와 update는 유일 키 위반이면 `DuplicateUserException`(409, unchecked)을 던집니다. `MULTI_INSTANCE`에서는 `ver`를 쓴 뒤 바로 읽히게(read-after-write) 하는 것을 권합니다.

- `ForwardingUserStore` — 데코레이터(캐시·테넌트·감사)는 반드시 이 추상 클래스를 상속합니다. 모든 메서드를 감싼 store로 넘기되, 다섯 필드 메서드는 감싼 store의 `supportsAtomicFieldWrites()`가 true일 때만 넘기고, 아니면 `UserStore`의 default를 데코레이터 위에서 돌려 데코레이터의 `findById`·`update`를 거칩니다(캐시가 무효화됨). `protected delegate()`.
- `InMemoryUserStore` — 메모리 store. 스레드 안전, id는 1부터 늘어나는 10진 문자열, `list`는 이름에 `search`가 든 사용자를 만든 순서로.
- `FileUserStore(File)`, `FileUserStore(File, long maxFileBytes, Logger)` — JSON 파일 하나 `{"format":"naru-users","version":1,"nextId":..,"users":[...]}`에 사용자를 둡니다. 만들 때 읽고, 바뀔 때마다 저장 파일 규약([저장 파일의 내구성](#저장-파일의-내구성))으로 원자적으로 다시 씁니다. 행마다 비밀번호 해시를 그대로 씁니다. id는 파일에 둔 카운터에서 나오므로 지운 id는 재시작 뒤에도 재사용하지 않습니다. 모르는 버전이거나 크기 한도(기본 8 MB, `FileUserStore.DEFAULT_MAX_FILE_BYTES`)를 넘는 파일은 `FAIL`로 시작하는 메시지로 생성이 실패하고 파일은 덮어쓰지 않습니다. 쓸 때마다 파일 전체를 다시 쓰므로 작은 설치에 맞고, `MULTI_INSTANCE`는 거부합니다. 파일 하나에는 쓰는 쪽이 하나입니다. 이 인스턴스가 읽거나 쓴 뒤 다른 쓰는 쪽이 파일을 바꿨으면 쓰기를 거부합니다([저장 파일의 내구성](#저장-파일의-내구성)). `getFile()`. `FileUserStore(File, AuthFileKey)`, `FileUserStore(File, long maxFileBytes, Logger, AuthFileKey)`로 키를 주면 해시를 `enc(…)`로 쓰고 파일은 형식 버전 2가 됩니다([파일 비밀 값 암호화](#파일-비밀-값-암호화-authfilekey)). 네 생성자 모두 파일을 읽고 `IOException`(checked)을 던지므로 저장소를 만드는 메서드가 선언하거나 잡습니다.

```java
UserStore store = new ForwardingUserStore(new InMemoryUserStore()) {
    @Override
    public AuthUser findById(String id) throws Exception {
        return super.findById(id);            // 캐시를 붙이는 자리
    }
};
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).users(store).build();
```

```kotlin
val store = object : ForwardingUserStore(InMemoryUserStore()) {
    override fun findById(id: String): AuthUser? = super.findById(id)
}
val auth = Auth.builder(System.getenv("JWT_SECRET")).users(store).build()
```

## 앱용 사용자 파사드: auth.users()

`auth.users()`는 `UserStore`가 아니라 `AuthUsers`를 돌려줍니다. 이름을 정규화하고, 비밀번호를 해시하고(`pbkdf2-sha256$<반복>$<솔트>$<해시>`), 출처 `APP`으로 `before*`/`on*` 이벤트를 내고, 사용자별 락 안에서 씁니다. 돌려주거나 mutator에 넘기는 `AuthUser`는 모두 `passwordHash`가 null인 사본이라 `res.json(auth.users().findById(id))`에 해시가 실리지 않습니다. 던지는 예외는 `DuplicateUserException`(409), `AuthUnavailableException`(503, store 예외 포함), `IllegalArgumentException`(400), `AuthRejection`(리스너 거부) 넷뿐입니다. 잘못된 인자, 예를 들어 `PasswordPolicy`를 어긴 비밀번호나 바꿀 수 없는 필드를 바꾸는 `update`는 `IllegalArgumentException`으로 거부합니다. 실제 클래스는 바뀔 수 있는 내부 하위 클래스이므로 이 타입으로 잡습니다. 앱 핸들러에서 던진 비밀번호 정책·사용자 이름 거부는 `auth.errors()`에서 그 메시지와 함께 400 `BAD_REQUEST`가 되고(예: `the password must have at least 8 characters`), 그 밖의 인자 거부는 메시지 없는 400입니다. 다르게 답하려면 핸들러에서 `IllegalArgumentException`을 catch하세요(예: 422).

| API | 설명 |
|---|---|
| `@Nullable findById(id)`, `@Nullable findByUsername(name)` | 해시를 뺀 사본 |
| `create(name, char[] password, roles...)` | 해시·정규화·이벤트 뒤 id가 있는 사용자 |
| `create(name, @Nullable char[] password, @Nullable UserMutator init, roles...)` | `init`이 속성과 서비스 계정 표시를 정합니다. 서비스 계정만 비밀번호가 null |
| `@Nullable update(id, UserMutator)` | 속성만 바꿉니다. id·이름·해시·ver·enabled·역할·서비스 계정을 바꾸면 `IllegalArgumentException` |
| `@Nullable rename(id, newName)` | `newName`을 정규화(NFKC, `Locale.ROOT` 소문자)해 저장하고 `UserChange.USERNAME`, 출처 `APP`으로 `beforeUserUpdate`/`onUserUpdated`를 냅니다. 지금 이름과 같으면 쓰기도 이벤트도 없이 돌아오고, 다른 사용자가 가진 이름이면 `DuplicateUserException`(409)이며 행은 그대로입니다 |
| `boolean delete(id)` | 그룹 소속을 먼저 지우고, (`.apiKeys(store)`가 있으면) 그 사용자의 키를 모두 지운 뒤 행을 지웁니다. 한 단계가 실패하면 503이고 행은 남으므로, 다시 부르면 멈춘 곳부터 이어 갑니다 |
| `boolean checkPassword(id, char[] password)` | 현재 비밀번호 확인. 없는 사용자나 null 해시는 더미 해시 뒤 false. 한 사용자 id의 현재 비밀번호 확인은 모두 하나의 한도(15분에 5번 실패)를 함께 씁니다. 넘으면 해시를 계산하지 않고 `getRetryAfterSeconds()`가 있는 `AuthRejection(429, "too_many_attempts")`을 던지고, `auth.errors()`는 `Retry-After`와 함께 429로 답합니다. 같은 사용자의 다른 확인(엔드포인트 포함)이 돌고 있으면 최대 5초 기다리고, 그래도 끝나지 않으면 `AuthUnavailableException`(503)입니다. 성공해도 횟수는 초기화되지 않습니다 |

로그인 값은 불투명한 `username` 문자열 하나입니다. auth는 NFKC와 `Locale.ROOT` 소문자만 적용하고 이메일·전화·로그인 키 API는 없습니다. `email` 같은 속성으로 로그인시키는 앱은 그 속성을 직접 정규화하고 유일성을 보장하지 않는 한 `allowedAttributes`에 넣으면 안 됩니다. auth는 둘 다 하지 않습니다. `create`나 `checkPassword`에 넘긴 `char[]` 비밀번호는 호출이 끝나면 `'\0'`만 담기므로, 계속 쓸 값이면 먼저 복사하세요.

auth를 거치는 쓰기는 사용자마다 하나인 재진입 락(store의 기본 필드 메서드가 잡는 락과 같은 것) 안에서 행을 다시 읽고 자기 필드만 바꿉니다. 두 관리자가 같은 사용자를 동시에 쓰면 마지막에 쓴 쪽이 이깁니다(last-writer-wins): 나중 쓰기가 먼저 쓰기를 덮습니다.

`UserMutator`는 `@FunctionalInterface AuthUser apply(AuthUser user)`입니다(`java.util.function.Function`은 API 24+). `auth.userStore()`는 원래 store를 돌려줍니다. `AuthUsers`가 준 사본을 그 store의 `update`에 넘기면 해시가 null로 저장되니 넘기지 않습니다.

Express 대응: 비밀번호를 해시해 저장하는 앱의 사용자 서비스(`User.create` + `bcrypt.hash`, 로그인의 `bcrypt.compare`).

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).build();
AuthUser alice = auth.users().create("Alice", "correct horse".toCharArray(), "editor");
auth.users().update(alice.getId(), u -> u.toBuilder().attribute("nickname", "Al").build());
boolean ok = auth.users().checkPassword(alice.getId(), "correct horse".toCharArray());
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).build()
val alice = auth.users().create("Alice", "correct horse".toCharArray(), "editor")
auth.users().update(alice.id!!) { it.toBuilder().attribute("nickname", "Al").build() }
```

## 그룹: GroupStore와 GroupLimits

그룹은 DAG입니다(한 그룹이 부모를 여럿 가질 수 있음). 그룹 id는 소문자 `[a-z0-9._-]{1,64}`, 소속은 사용자 id로 묶으므로 이름을 바꿔도 소속이 남습니다.

- 구현할 메서드는 둘입니다: `Set<String> groupsOfUser(String userId)`(직접 소속), `Set<String> parentsOf(Set<String> groupIds)`(직접 부모의 합집합). 둘 다 `throws Exception`이고, 한 그룹짜리 `parentsOf(String)`은 없습니다(N+1 쿼리 방지).
- `default effectiveGroupsOfUser(userId)` — 위로 한 단계마다 `parentsOf`를 한 번 부릅니다. 다이아몬드와 순환은 한 번만 셉니다. 깊이 8 또는 크기 256을 넘으면 **자르지 않고** 503 `SERVICE_UNAVAILABLE`로 실패합니다(요청을 허용하지 않음). DB가 재귀 CTE를 쓸 수 있으면 재정의하세요. auth는 재정의한 결과의 크기도 다시 검사합니다.
- `default rolesOfGroups(groupIds)` — 그룹이 주는 역할. 인터페이스 기본은 빈 집합이고, 내장 `InMemoryGroupStore`·`FileGroupStore`는 `grantRole`로 준 역할의 합집합을 돌려줍니다.
- `GroupLimits.defaults()`(깊이 8, 크기 256), `GroupLimits.of(maxDepth, maxSize)`, `getMaxDepth()`, `getMaxSize()`.

### 그룹 쓰기: GroupAdminStore, InMemoryGroupStore, FileGroupStore

`GroupAdminStore extends GroupStore`는 관리 그룹 엔드포인트용 쓰기입니다. LDAP처럼 읽기만 하는 출처는 `GroupStore`만 구현합니다.

| 메서드 | 설명 |
|---|---|
| `boolean createGroup(id)` | 이미 있으면 false |
| `boolean deleteGroup(id)` | 소속과, 그 그룹이 자식이나 부모인 간선을 함께 지웁니다. 없으면 false |
| `listGroups()`, `groupExists(id)`, `membersOf(id)`, `childrenOf(id)` | 조회 |
| `addMember`, `removeMember`, `addParent(child, parent)`, `removeParent` | 멱등. 같은 호출을 두 번 해도 한 번과 같고 오류가 없습니다 |
| default `removeUser(userId)` | 사용자 삭제 때 모든 직접 소속을 지웁니다. DB store는 `DELETE` 한 번으로 재정의 |

내장 두 store는 그룹에 역할을 주는 메서드를 더 가집니다(관리 HTTP 경로는 0.11.0에 없고 다음 auth 계약인 v2에서 다룹니다).

| 메서드 | 설명 |
|---|---|
| `grantRole(groupId, role)` | 그룹에 역할을 줍니다. 그 그룹의 구성원(직접이든 자식 그룹을 거쳐서든)이 역할의 스코프를 받습니다. 두 번 줘도 한 번과 같습니다. 없는 그룹, 빈 역할, 탭·CR·LF가 든 역할은 `IllegalArgumentException`이고 아무것도 바꾸지 않습니다. `FileGroupStore`는 파일에 쓰고, 쓰기 실패는 `IOException`(메모리도 그대로) |
| `revokeRole(groupId, role)` | 역할을 거둡니다. 없으면 아무 일도 하지 않습니다 |
| `rolesOf(groupId)` | 그 그룹 자신이 가진 역할(부모의 역할은 빼고) |

`deleteGroup`은 그 그룹의 역할도 지웁니다.

**store에 직접 쓴 변경이 보이는 때:** 기동 뒤에 store에 직접 쓴 변경은 모두, 곧 소속(`addMember`, `removeMember`), 부모 간선(`addParent`, `removeParent`), 역할(`grantRole`, `revokeRole`), 그룹 삭제(`deleteGroup`)는, 로그인한 사용자에게 그 사용자의 캐시된 주체가 만료될 때 반영됩니다. 기본은 5초입니다(`userCacheTtl`, [주체 캐시](#주체-캐시-usercachettl)). 관리 그룹 엔드포인트([관리 엔드포인트](#관리-엔드포인트-authadminroutes))를 거친 변경은 그 노드에서 바로 반영됩니다. 테스트에서 store에 쓰고 바로 요청을 보낸다면 `Auth.builder(...).groups(groups).userCacheTtl(0)`으로 빌드해 요청마다 store를 읽게 합니다.

auth는 `addParent` 전에 순환(자식이 이미 부모의 조상)과 자기 간선, 깊이(부모 위 최장 경로 + 1 + 자식 아래 최장 경로가 8 초과)를 검사하고, 걸리면 `addParent`를 부르지 않고 409 `group_cycle`로 답합니다. `InMemoryGroupStore`는 이 검사와 삽입을 한 락 안에서 하므로 동시에 들어온 두 간선이 순환을 만들 수 없습니다. `FileGroupStore(File)`은 바뀔 때마다 파일을 원자적으로 다시 쓰고 생성자에서 읽습니다. 파일 첫 줄은 형식 버전 `naru-groups 1`이고, 모르는 버전이면 그 버전을 적은 오류로 열기를 거부합니다. 파일은 크기 한도(기본 8 MB, `FileGroupStore.DEFAULT_MAX_FILE_BYTES`) 안에서 읽고 씁니다. 한도는 읽기 전에 파일 길이와 비교하므로 아주 크거나 망가진 파일은 힙을 다 쓰지 않고 `FAIL ... above the limit` 메시지로 생성자가 실패하고, 파일을 한도 위로 키울 변경은 `IOException`으로 거부하며 메모리와 파일은 이전 상태 그대로입니다. 한도는 `new FileGroupStore(file, limits, maxFileBytes, logger)`로 정합니다. 둘 다 노드끼리 공유되지 않으니 `MULTI_INSTANCE`는 거부합니다. 간선 검사의 한도는 store 생성자에 준 `GroupLimits`이니, 빌더의 `.groupLimits(...)`와 같은 값을 넘깁니다.

Express 대응: passport `deserializeUser`에서 그룹 테이블을 조인해 역할을 모으는 코드.

```java
InMemoryGroupStore groups = new InMemoryGroupStore();
groups.createGroup("company");
groups.createGroup("eng");
groups.addParent("eng", "company");     // eng ⊂ company
groups.grantRole("company", "editor");  // company와 그 하위 그룹(eng) 구성원은 editor
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).groups(groups)
    .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())
    .build();
AuthUser bob = auth.users().create("bob", "bob-password-1".toCharArray());
groups.addMember("eng", bob.getId());   // bob은 auth.require("posts:write")를 통과
// 기동 뒤 store에 직접 쓴 변경(groups.addMember(...))은 userCacheTtl 안에 로그인한 사용자에게 반영
// (기본 5초, 테스트에서는 .userCacheTtl(0))
```

```kotlin
val groups = InMemoryGroupStore()
groups.createGroup("eng")
groups.grantRole("eng", "editor")
val auth = Auth.builder(System.getenv("JWT_SECRET")).groups(groups)
    .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())
    .build()
val bob = auth.users().create("bob", "bob-password-1".toCharArray())
groups.addMember("eng", bob.id!!)
```

## 자체 데이터베이스 연결

auth에는 내장 데이터베이스 저장소가 없습니다. 영속화는 SPI로만 합니다: 사용자는 `UserStore`, 그룹은 `GroupStore`(그룹 관리 엔드포인트를 쓰면 `GroupAdminStore`), refresh 토큰은 `RefreshTokenStore`, 로그인 제한은 `LoginThrottle`, 신뢰 기기는 `DeviceTrust`, API 키는 `ApiKeyStore`입니다. 앱은 이 인터페이스의 메서드를 자기 DB 호출(JDBC, JPA, MyBatis, Room 등)에 연결하고, 드라이버·표·마이그레이션도 앱이 정합니다. auth가 주는 저장소는 노드 하나용인 인메모리와 파일 기본값뿐입니다.

`deployment(MULTI_INSTANCE)`는 auth가 주는 인메모리·파일 저장소(`InMemoryUserStore`, `FileUserStore`, `InMemoryGroupStore`, `FileGroupStore`, `InMemoryRefreshTokenStore`, `FileRefreshTokenStore`, `InMemoryLoginThrottle`, `InMemoryApiKeyStore`)와, 지정하지 않아 기본값으로 떨어지는 저장소를 `build()`에서 `AuthConfigException`으로 거부합니다. 이 검사는 저장소의 클래스를 보므로, 인메모리 저장소를 감싼 데코레이터는 알아보지 못합니다. 그러니 노드끼리 공유하는 저장소를 모두 지정합니다: `.users(..)`, `.refreshTokens(..)`, `.loginThrottle(..)`, `.deviceTrust(..)`, 그룹을 쓰면 `.groups(..)`, API 키를 켜면 `.apiKeys(..)`.

`deployment(MULTI_INSTANCE)`의 저장소가 해야 하는 원자성 선언:

| 선언 | 저장소 | true를 돌려줄 조건 | 선언하지 않으면 |
|---|---|---|---|
| `supportsAtomicFieldWrites()` | `UserStore` | 아래 문단 | doctor FAIL, 앱이 시작하지 않습니다 |
| `supportsAtomicLimitInsert()` | `ApiKeyStore` | `insertIfUnderLimit`이 살아 있는 키 세기와 넣기를 한 잠금 안에서 합니다(예: 소유자 행을 `SELECT … FOR UPDATE`로 잠근 트랜잭션) | `.apiKeys(..)`를 켰을 때 doctor FAIL |
| `usesLimitArguments()` | `LoginThrottle` | 한도 인자 메서드 `retryAfter(key, max, windowMillis)`·`recordFailure(key, max, windowMillis)`가 넘겨받은 한도를 씁니다(예: 실패마다 업서트 한 문장으로 `failures = failures + 1`) | 저장소가 auth가 넘긴 한도(`.loginFailureLimits(..)`, `.apiKeyFailureLimit(..)`)를 무시하고 자기 한도로 셉니다. API 키를 켰을 때 doctor WARN |

다섯 필드 메서드가 각각 원자적 쓰기 한 번(SQL 데이터베이스라면 조건부 `UPDATE` 한 문장)이고 `update()`가 `passwordHash`, `ver`, `enabled`, `roles`를 쓰지 않을 때만 `supportsAtomicFieldWrites()`가 `true`를 돌려줍니다. 다섯 필드 메서드는 `updatePasswordHash`, `changePassword`, `setEnabled`, `bumpTokenVersion`, `setRoles`입니다. 성공은 갱신 행 수로 정하고 읽고-고쳐-쓰기를 하지 않습니다. `ver`는 올리기만 하고, 유일 키 위반은 `DuplicateUserException`으로 바꾸고, 지운 id는 다시 쓰지 않습니다. 만든 저장소는 `AuthTesting.verifyUserStore(store)`와 `AuthTesting.verifyApiKeyStore(a, b)`로 계약을 확인합니다.

아래는 SQL 데이터베이스 위의 앱 `UserStore` 뼈대입니다. 라이브러리가 싣는 코드가 아니라 앱에 두는 예시이고(드라이버 없이 컴파일됩니다), 표 `users(id, username, password_hash, ver, enabled, roles, attributes, service_account)`는 앱의 마이그레이션 도구가 만듭니다. `changePassword`와 다른 필드 메서드는 모두 조건부 쓰기 한 문장입니다. 관리 엔드포인트 `GET /auth/admin/users`를 쓰면 `list(offset, limit, search)`도 `ORDER BY username LIMIT ? OFFSET ?`로 구현합니다(기본 구현은 `UnsupportedOperationException`을 던집니다).

```java
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import javax.sql.DataSource;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 앱 코드: SQL 데이터베이스 위의 UserStore. 표 users는 앱의 마이그레이션 도구가 만듭니다. */
public abstract class SqlUserStore implements UserStore {
    private static final String COLUMNS = "id, username, password_hash, ver, enabled, roles, attributes, service_account";
    private final DataSource db;

    protected SqlUserStore(DataSource db) {
        this.db = db;
    }

    /** roles·attributes를 TEXT 열로 바꾸고 행을 AuthUser로 읽는 일은 앱의 JSON 코덱이 맡습니다. */
    protected abstract String rolesText(Set<String> roles);

    protected abstract String attributesText(Map<String, String> attributes);

    protected abstract AuthUser read(ResultSet row) throws SQLException;

    @Override
    public AuthUser findByUsername(String username) throws SQLException {
        return findOne("username", username);
    }

    @Override
    public AuthUser findById(String id) throws SQLException {
        return findOne("id", id);
    }

    @Override
    public AuthUser create(AuthUser user) throws SQLException {
        AuthUser row = user.toBuilder().id(UUID.randomUUID().toString()).build(); // 노드끼리 겹치지 않고 지운 id를 다시 쓰지 않습니다
        write("INSERT INTO users (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)", row.getId(), row.getUsername(),
                row.getPasswordHash(), row.getTokenVersion(), row.isEnabled(), rolesText(row.getRoles()),
                attributesText(row.getAttributes()), row.isServiceAccount());
        return row;
    }

    @Override
    public AuthUser update(AuthUser user) throws SQLException {
        // username과 attributes만 씁니다. password_hash·ver·enabled·roles는 필드 메서드로만 바뀝니다.
        int n = write("UPDATE users SET username=?, attributes=? WHERE id=?", user.getUsername(),
                attributesText(user.getAttributes()), user.getId());
        return n == 0 ? null : findById(user.getId());
    }

    @Override
    public boolean delete(String id) throws SQLException {
        return write("DELETE FROM users WHERE id=?", id) > 0;
    }

    @Override
    public boolean updatePasswordHash(String id, String expectedOldHash, String newHash) throws SQLException {
        if (expectedOldHash == null) {
            return write("UPDATE users SET password_hash=? WHERE id=? AND password_hash IS NULL", newHash, id) == 1;
        }
        return write("UPDATE users SET password_hash=? WHERE id=? AND password_hash=?", newHash, id, expectedOldHash) == 1;
    }

    @Override
    public boolean changePassword(String id, String expectedOldHash, long expectedVer, String newHash)
            throws SQLException {
        // 해시와 ver를 한 문장으로 바꿉니다. 이전 해시가 null이면 null과 맞춥니다.
        return write("UPDATE users SET password_hash=?, ver=ver+1 WHERE id=? AND ver=? AND (password_hash=? OR (password_hash IS NULL AND ? IS NULL))",
                newHash, id, expectedVer, expectedOldHash, expectedOldHash) == 1;
    }

    @Override
    public boolean setEnabled(String id, boolean enabled) throws SQLException {
        return write("UPDATE users SET enabled=? WHERE id=?", enabled, id) == 1;
    }

    @Override
    public boolean bumpTokenVersion(String id) throws SQLException {
        return write("UPDATE users SET ver=ver+1 WHERE id=?", id) == 1;
    }

    @Override
    public boolean setRoles(String id, Set<String> roles) throws SQLException {
        return write("UPDATE users SET roles=? WHERE id=?", rolesText(roles), id) == 1;
    }

    // 다섯 필드 메서드가 모두 조건부 UPDATE 한 문장이므로 true입니다.
    @Override
    public boolean supportsAtomicFieldWrites() {
        return true;
    }

    private AuthUser findOne(String column, String value) throws SQLException {
        try (Connection c = db.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM users WHERE " + column + "=?")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    /** 한 문장을 자동 커밋으로 실행하고 갱신 행 수를 돌려줍니다. 유일 키 위반(PostgreSQL SQLState 23505, MySQL 오류 1062. 다른 DB는 그 DB의 코드)은 DuplicateUserException입니다. */
    private int write(String sql, Object... args) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) {
                    ps.setNull(i + 1, Types.VARCHAR);
                } else {
                    ps.setObject(i + 1, args[i]);
                }
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState()) || e.getErrorCode() == 1062) {
                throw new DuplicateUserException("the username is taken", e);
            }
            throw e;
        }
    }
}
```

다른 저장소도 같은 방식으로 앱이 구현해 빌더에 넘깁니다.

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .deployment(Deployment.MULTI_INSTANCE)
    .users(SqlUsers(dataSource))                  // 앱의 UserStore: supportsAtomicFieldWrites()가 true
    .refreshTokens(SqlRefreshTokens(dataSource))  // 앱의 RefreshTokenStore
    .loginThrottle(SqlLoginThrottle(dataSource))  // 앱의 LoginThrottle: usesLimitArguments()가 true
    .deviceTrust(SqlDeviceTrust(dataSource))      // 앱의 DeviceTrust
    .apiKeys(SqlApiKeys(dataSource))              // 앱의 ApiKeyStore: supportsAtomicLimitInsert()가 true
    .build()
```

Express 대응: Passport의 `serializeUser`/`deserializeUser`와 Knex·Sequelize 모델처럼, 사용자 테이블은 앱이 붙이고 스키마는 앱의 마이그레이션 파일로 둡니다.

## 시간: TimeSource

`com.snoworca.naruserve.utils.TimeSource`는 `@FunctionalInterface long nowMillis()`입니다(`java.time`은 API 26+). auth의 공개 시각은 모두 이 소스에서 읽은 epoch 밀리초 `long`/`Long`입니다. `TimeSource.system()`이 시스템 시계이고, 빌더 `.timeSource(...)`로 테스트에서 시계를 고정합니다. Express 대응: 테스트에서 `Date.now`를 바꾸는 것.

```java
TimeSource fixed = () -> 1_000L;
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).timeSource(fixed).build();
```

```kotlin
val fixed = TimeSource { 1_000L }
```

## 역할과 스코프: RolePolicy, RoleStore, Scopes

스코프는 `resource:action` 두 부분이고 각 부분은 `[a-z0-9-]{1,32}`입니다. 와일드카드는 부여하는 쪽에만 `resource:*`와 `*` 두 꼴로 씁니다. `*`는 모두를, `posts:*`는 `posts:*`와 모든 `posts:<action>`을, `posts:read`는 자신만 덮습니다. `write`가 `read`를 포함하지 않습니다. `auth-`로 시작하는 리소스는 예약되어 `auth-users`, `auth-groups`, `auth-apikeys`만 받습니다. 역할 이름과 스코프는 대소문자를 구분합니다. `Editor`와 `editor`는 다른 역할이고(`RolePolicy`에 없는 쪽에 doctor가 `undeclared role` WARN을 냅니다), `Posts:read`는 스코프 문법에 맞지 않습니다.

| API | 설명 | Express 대응 |
|---|---|---|
| `RolePolicy.builder().role(name, scopes...).build()` | 코드로 선언한 역할. 내장 `admin`(`*`)에 합쳐집니다. 문법이 틀리거나 예약된 스코프면 그 자리에서 `IllegalArgumentException` | `accesscontrol`의 `grant(...)` |
| `admin` 다시 선언 | 합집합이 되고 `build()`가 WARN을 한 번 남깁니다 | — |
| `.replaceBuiltinRoles()` | 내장 역할을 버립니다(`admin`은 직접 준 것만 가짐) | — |
| `.logger(Logger)` | `build()`의 경고를 받을 곳. 기본은 새 `ConsoleLogger` | — |
| `RolePolicy.builtin()`, `scopesOf(role)`, `roles()`, `grantedScopes()` | 내장 정책 / 역할의 스코프 / 역할 이름 / 모든 부여 스코프 | — |
| `RoleStore` (`@FunctionalInterface`, `scopesOf(role) throws Exception`) | DB 역할. 예약되었거나 틀린 행은 역할마다 WARN 한 번과 함께 버리고, 예외는 503으로 닫힙니다 | — |
| `Scopes.covers(grant, other)` | 부여가 덮는지. 둘 다 올바른 부여여야 하고 아니면 `IllegalArgumentException` | — |
| `Scopes.isValidGrant/isValidRequired`, `validateGrant/validateRequired` | 문법 검사. 요구 스코프에는 `*`가 올 수 없습니다 | — |

```java
RolePolicy policy = RolePolicy.builder()
    .role("editor", "posts:read", "posts:write")   // 내장 admin(*)은 그대로 유지된다
    .build();
AuthUser user = AuthUser.builder().id("7").username("alice").roles("editor").build();
boolean canWrite = false;
for (String role : user.getRoles()) {
    for (String grant : policy.scopesOf(role)) {
        canWrite |= Scopes.covers(grant, "posts:write");
    }
}
Long numericId = AuthIds.parseLong("7");           // "abc"면 null
```

```kotlin
val policy = RolePolicy.builder()
    .role("editor", "posts:read", "posts:write")
    .build()
val user = AuthUser.builder().username("alice").roles("editor").build()
val canWrite = user.roles.any { role -> policy.scopesOf(role).any { Scopes.covers(it, "posts:write") } }
println("${user.username} enabled=${user.isEnabled} canWrite=$canWrite")
```

## 저장 파일의 내구성

auth의 파일 저장(모든 auth 파일 store가 쓰는 내부 `AtomicFiles` 규약)은 임시 파일에 쓰고 `FileDescriptor.sync()`를 한 뒤, 기존 파일을 `.bak`로, 임시 파일을 본 이름으로 바꾸고 `.bak`를 지웁니다. 기동할 때 `.bak`만 있으면 그것을 되살리고 WARN을 남기며, 둘 다 있고 본 파일을 읽을 수 있으면 본 파일을 쓰고 `.bak`를 지웁니다. 본 파일을 읽을 수 없으면 아무것도 덮어쓰지 않고 기동을 멈춥니다(`.bak`를 확인하라는 FAIL 메시지). API 21에는 디렉터리 fsync가 없어서 이름 바꾸기의 내구성은 파일 시스템에 달려 있고, 전원이 꺼지면 `ver`(토큰 버전)가 한 단계 되돌아갈 수 있습니다. 임시 파일은 소유자만 들어갈 수 있는 임시 디렉터리(`.<이름>.tmp.new-<16진수>`) 안에서 소유자 전용으로 만든 뒤 제 이름으로 옮기고 나서 내용을 쓰므로, 쓰는 도중에도 umask 권한으로 보이는 순간이 없고, 비밀번호 해시와 토큰 버전이 든 저장 파일을 umask와 관계없이 다른 로컬 사용자가 읽지 못합니다(그런 권한이 없는 파일 시스템은 기본 권한을 그대로 씁니다). 저장소마다의 크기 한도는 읽기 전에 파일 길이와 비교하므로, 아주 크거나 망가진 파일은 힙을 다 쓰지 않고 `FAIL ... above the limit` 메시지로 기동이 실패합니다. 한 디렉터리 안의 쓰기는 프로세스 안에서 차례로 일어나고, 디렉터리는 정규 경로(canonical path)로 비교하며 파일 이름은 비교에 쓰지 않으므로, `dir/users.json`과 `dir/./users.json`, 심볼릭 링크를 거친 경로, `dir/USERS.json`(macOS·Windows처럼 대소문자를 가리지 않는 파일 시스템), 같은 이름의 NFC와 NFD 표기(APFS·HFS+처럼 유니코드 정규화를 가리지 않는 파일 시스템)도 파일이나 디렉터리가 아직 없든 있든 쓰는 쪽이 한 번에 하나뿐입니다. 아직 없는 디렉터리는 표기와 관계없이 프로세스 전체의 잠금 하나를 함께 쓰고, 그 디렉터리로의 쓰기는 아무것도 쓰지 않고 `IOException`으로 실패하며, 디렉터리가 없을 때 준비하던 쓰기가 그사이 디렉터리가 만들어져 있으면 역시 아무것도 쓰지 않고 실패하므로 다시 시도하면 디렉터리 잠금 아래에서 파일을 다시 확인합니다. 한 디렉터리의 서로 다른 파일은 쓰기 차례만 함께 기다리고 파일은 따로입니다. `FileUserStore`는 마지막으로 읽거나 쓴 파일 상태를 기억해 두고, 그 뒤 다른 쓰는 쪽(같은 파일 위의 다른 `FileUserStore`나 다른 프로세스)이 파일을 바꿨으면 그 변경(`ver`, 비밀번호 해시)을 되돌리지 않도록 쓰기를 `IOException`(auth 경로에서는 503)과 ERROR 한 번으로 거부합니다. 파일 하나에는 `FileUserStore` 하나를 쓰고, 파일을 다시 읽으려면 재시작합니다.

## 비밀번호 해시: PasswordHasher, HashCost

| API | 설명 | Express 대응 |
|---|---|---|
| `password.PasswordHasher` | `boolean accepts(String stored)`, `String hash(char[] password)`, `boolean verify(char[] password, String stored)`, default `boolean needsRehash(String stored)`(false). 앱이 구현할 수 있는 SPI이고, 0.11.0부터는 default 메서드만 더합니다. 저장 문자열의 접두사가 알고리즘을 정합니다. auth는 설정한 해시기 **하나**를 쓰고, 그 해시기가 `accepts`하지 않는 저장 문자열은 더미 해시와 비교한 뒤 false입니다. 여러 형식을 읽으려면(bcrypt 계정을 PBKDF2로 옮기기) `PasswordHashers.chain(...)`을 `.passwordHasher(...)`로 줍니다 | `bcrypt` 객체 |
| `new Pbkdf2PasswordHasher(iterations)` | 기본 해시기. `javax.crypto.Mac` 위에 직접 쓴 PBKDF2-HMAC-SHA256(Android 5.0에는 `PBKDF2WithHmacSHA256`이 없음). 형식 `pbkdf2-sha256$<반복 수>$<솔트>$<해시>`(16바이트 솔트, 32바이트 키, 패딩 없는 base64url). 빈 비밀번호는 `IllegalArgumentException`. HMAC 키는 해시마다 한 번만 설정하므로 긴 비밀번호도 비용이 같습니다. `needsRehash`는 저장 반복 수가 이 해시기 반복 수의 80% 미만이거나 125% 초과일 때 참. 저장 반복 수가 2,000,000을 넘으면 해시하지 않고 false(`MAX_STORED_ITERATIONS`). auth의 로그인과 비밀번호 확인에서는 형식이 깨졌거나 반복 수가 한도를 넘는 `pbkdf2-sha256$` 저장 문자열도 없는 사용자처럼 목표 비용의 더미 PBKDF2를 한 번 돌린 뒤 false이므로, 시간으로 구별되지 않습니다. `getIterations()` | `bcrypt.hashSync` / `compareSync` |
| `PasswordHashers.bcrypt()`, `BcryptPasswordHasher.builder().maxCost(n).cost(n).logger(logger).build()` | 레거시 bcrypt 검증기(라이브러리 없이 직접 구현). `$2a$`·`$2b$`·`$2y$`를 읽고 `$2x$`는 받지 않습니다. 저장 cost가 상한(기본 14)을 넘으면 키 스케줄을 돌리지 않고 false, ERROR 한 번. 비밀번호는 UTF-8 앞 72바이트만 셉니다. 솔트 22자·해시 31자·알파벳 `./A-Za-z0-9`가 아니면 false. `needsRehash`는 늘 참(다음 성공 로그인에서 PBKDF2로). `hash()`도 되지만(`$2b$`, 기본 cost 10) 기본 해시기는 PBKDF2입니다 | `bcrypt.compare` |
| `PasswordHashers.chain(primary, legacy...)`, `PasswordHashers.chain(logger, primary, legacy...)` | 여러 저장 형식을 해시기 하나로 읽습니다. 저장 문자열마다 `primary`, 이어서 `legacy`를 순서대로 보고 `accepts`가 처음 참인 해시기가 `verify`합니다. `hash`는 늘 `primary`입니다. `needsRehash`는 받아들인 해시기가 `primary`가 아니거나 `primary.needsRehash`가 참일 때 참이라, 다음 성공 로그인에서 `primary` 형식으로 옮겨집니다. 어느 해시기도 받지 않는 저장 문자열은 false이고, 접두사(앞 최대 4자, 문자열 전체는 절대 아님)마다 ERROR를 한 번 남깁니다(`accepts`와 `verify` 어느 쪽에서 만나도 한 번). 로거를 주지 않으면 `ConsoleLogger`입니다(`app.logger()`를 쓰려면 `chain(app.logger(), ...)`). 직접 구현한 `PasswordHasher`(Java, Kotlin)도 멤버가 됩니다 | 형식마다 `bcrypt.compare` 등을 고르는 코드 |
| `PasswordHashers.digest(prefix, algorithm, encoding)` | 솔트 없는 레거시 MD5, SHA-1, SHA-256 검증기. 저장 형식 `<prefix>$<digest>`(예: `digest("legacy-md5", "MD5", DigestEncoding.HEX)`는 `legacy-md5$5f4dcc3b...`를 읽음). `algorithm`은 `MessageDigest` 이름(`"MD5"`, `"SHA-1"`, `"SHA-256"`)이고 없는 이름은 만들 때 `IllegalArgumentException`. 비밀번호는 UTF-8로 다이제스트하고 `ConstantTime`으로 비교합니다. `prefix`는 `$` 없는 이름이고 끝에 `$` 하나를 붙여 줘도 겹치지 않습니다. 빈 접두사는 거부합니다. 컬럼에 접두사가 없으면 저장소가 읽을 때 붙입니다(`"legacy-md5$" + column`). `hash()`는 `UnsupportedOperationException`(약한 해시를 새로 만들지 않음), `needsRehash`는 늘 참 | `crypto.createHash('md5').update(pw).digest('hex')` 비교 |
| `PasswordHashers.saltedDigest(prefix, algorithm, encoding, order)` | 솔트 있는 레거시 다이제스트 검증기. 저장 형식 `<prefix>$<salt>$<digest>`(예: `legacy-sha256$Xy7q$9f86d0...`). 솔트는 접두사 뒤부터 **마지막** `$` 앞까지의 글자 그대로이고(그래서 솔트에 `$`가 있어도 됨), UTF-8로 비밀번호와 이어 붙입니다. `order`는 `SaltOrder.SALT_THEN_PASSWORD`(`sha256(salt + pw)`) 또는 `SaltOrder.PASSWORD_THEN_SALT`(`sha256(pw + salt)`)이고 옛 코드를 보고 고릅니다. 솔트 컬럼이 따로 있으면 저장소가 읽을 때 합칩니다. 나머지는 `digest`와 같습니다 | `crypto.createHash('sha256').update(salt + pw)` 비교 |
| `DigestEncoding.HEX`, `DigestEncoding.BASE64` | `digest`와 `saltedDigest`의 다이제스트 표기. `HEX`는 대소문자 모두, `BASE64`는 표준 알파벳(`+`, `/`)이고 `=` 패딩은 있어도 없어도 됩니다 | `digest('hex')` / `digest('base64')` |
| `HashCost.calibrate(targetMillis)`, `HashCost.fixed(iterations)`, `HashCost.defaults()` | 이 기기를 한 번 재서(짧은 예열과 측정 한 번) 한 해시가 목표 시간쯤 걸리는 반복 수를 고릅니다(10,000 단위 내림, 50,000–600,000) / 보정 없이 고정 반복 수 / `calibrate(500)`. `isFixed()`, `getTargetMillis()`(고정이면 0), `getIterations()`(보정이면 0) | `bcrypt.hash(pw, rounds)`의 rounds |

auth는 모든 `hash`·`verify`를 해시 슬롯(`.hashConcurrency(n)`, 기본 `max(1, 코어/2)`, 대기열은 슬롯의 4배) 안에서 돌립니다. 설정한 해시기가 bcrypt와 PBKDF2를 함께 다뤄도 모두 같은 슬롯을 나눠 씁니다. 슬롯을 5초 안에 얻지 못하면 `AuthUnavailableException`(503, `Retry-After: 1`)입니다. 비용은 처음 쓸 때(보통 기동 doctor, `.user` 비밀번호가 있으면 `build()`) 한 번 잽니다. 보정 목표 시간은 로그인 응답 시간 기준 T로도 쓰고, `fixed(n)`이면 기동 때 n 반복 해시를 한 번 잰 시간이 T입니다(n은 바뀌지 않음). 정한 것: 기본 보정 목표는 500 ms(권장 범위 250–500 ms의 위쪽 끝. 요즘 서버에서 OWASP 권장 600,000회에 닿고, 느린 기기는 `HashCost.calibrate(250)`을 씁니다). 250–500 ms 밖의 보정 목표는 잘라 맞추지 않고 `build()`가 `AuthConfigException`으로 거부합니다(DEV 포함). `HashCost.fixed(n)`이 50,000회 미만이면 doctor가 PROD에서 FAIL, DEV에서 WARN입니다(AuthMode 표의 "해시 비용 실패").

레거시 형식 옮기기: `primary`가 아닌 해시기로 로그인이 성공하면(PBKDF2 비용이 목표 밖일 때도) auth는 해시 슬롯을 놓은 뒤 재해시를 낮은 우선순위 대기열에 넣고, 응답은 재해시를 기다리지 않습니다. 대기열은 최대 100개이고 가득 차면 그 재해시를 바로 버립니다(로그인을 막지 않고, 그 사용자의 다음 성공 로그인에서 다시 넣습니다). 데몬 작업 스레드 하나가 같은 해시 슬롯을 얻어 `primary`로 해시하고, 사용자 잠금 안에서 행을 다시 읽어 로그인 때의 `passwordHash`, ver, enabled, roles가 그대로일 때만 `UserStore.updatePasswordHash(id, 옛 해시, 새 해시)`로 씁니다. 그 사이 바뀌었으면(관리자 비활성화 등) 조용히 건너뜁니다. 대기열의 평문 비밀번호는 `char[]`로만 들고, 처리하거나 버린 즉시 `'\0'`으로 지웁니다. 해시나 저장 실패(예: 레거시 `VARCHAR(32)` 컬럼이 PBKDF2 문자열보다 짧음)는 WARN 한 줄이고 로그인 결과를 바꾸지 않습니다. 내장 비밀번호 로그인(`POST /auth/login`)이 이 대기열에 재해시를 넣습니다.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .hashCost(HashCost.calibrate(250))      // 느린 Android 기기
    .hashConcurrency(2)
    .build();
PasswordHasher hasher = new Pbkdf2PasswordHasher(600_000);
String stored = hasher.hash("correct horse".toCharArray());
boolean ok = hasher.verify("correct horse".toCharArray(), stored);
boolean legacy = PasswordHashers.bcrypt().verify("rasmuslerdorf".toCharArray(),
    "$2y$10$.vGA1O9wmRjrwAVXD98HNOgsNpDczlqm3Jq7KnEd1rVAGv3Fykk1a");

// 옛 계정을 PBKDF2로 옮기기: 새 해시는 첫 번째(PBKDF2), 옛 형식은 접두사로 골라 검증
Auth migrating = Auth.builder(System.getenv("JWT_SECRET"))
    .passwordHasher(PasswordHashers.chain(new Pbkdf2PasswordHasher(600_000),
        PasswordHashers.bcrypt(),                                              // $2a$/$2b$/$2y$
        PasswordHashers.digest("legacy-md5", "MD5", DigestEncoding.HEX),       // legacy-md5$<32 hex>
        PasswordHashers.saltedDigest("legacy-sha256", "SHA-256", DigestEncoding.HEX,
            SaltOrder.SALT_THEN_PASSWORD)))                                    // legacy-sha256$<salt>$<hex>
    .build();
```

```kotlin
val hasher: PasswordHasher = Pbkdf2PasswordHasher(600_000)
val stored = hasher.hash("correct horse".toCharArray())
val ok = hasher.verify("correct horse".toCharArray(), stored)
val cost = HashCost.calibrate(250)
println("ok=$ok rehash=${hasher.needsRehash(stored)} target=${cost.targetMillis} ms")
val chain: PasswordHasher = PasswordHashers.chain(hasher, PasswordHashers.bcrypt(),
    PasswordHashers.digest("legacy-md5", "MD5", DigestEncoding.HEX))
val md5 = "legacy-md5$5f4dcc3b5aa765d61d8327deb882cf99"
println("legacy=${chain.verify("password".toCharArray(), md5)} rehash=${chain.needsRehash(md5)}")
```

## 키 세트와 회전: KeySource, JwtKeys, KeySources

| API | 설명 | Express 대응 |
|---|---|---|
| `JwtKeys.builder().signingKey(kid, key).verificationKey(kid, key).build()` | kid로 찾는 키 세트. 서명 키는 정확히 하나(0개나 2개면 `IllegalStateException`), 검증 키는 0개 이상. `getSigningKid()`, `getSigningKey()`(사본), `@Nullable verificationKey(kid)`(서명 키 포함, 사본), `getKids()`. `toString()`은 키를 보이지 않습니다 | JWKS의 키 배열 |
| `JwtKeys.of(key)`, `JwtKeys.of(kid, key)` | 키 하나짜리 세트. kid를 주지 않으면 키의 SHA-256 앞 8자(base64url)라 같은 키는 노드·재시작과 무관하게 같은 kid입니다 | — |
| `KeySources.rotating(JwtKeys)` | 회전: 새 토큰은 서명 키와 그 kid로 서명하고, 세트에 남은 kid를 단 옛 토큰도 검증됩니다. refresh 수명이 지나면 옛 키를 세트에서 뺍니다 | — |
| `KeySources.fromFile(File)` | 파일이 없으면 `SecureRandom` 32바이트로 한 번 만들고 소유자만 읽고 쓰게 합니다. 있으면 읽기만 하고 절대 덮어쓰지 않습니다(덮어쓰면 모두 로그아웃). 없는 경로를 동시에 만드는 쪽(두 `Auth` 빌드, 한 데이터 디렉터리의 두 프로세스)은 옆의 잠금 파일 `.<이름>.lock`(남겨 둠)으로 차례를 지켜 모두 디스크에 남은 한 키를 씁니다. `build()` 때 읽습니다. Android 자동 백업에서 이 파일을 빼세요(두 설치가 서명 키를 나눠 갖게 됨). `KeySources.fromFile(File, AuthFileKey)`는 키를 `enc(…)`로 저장합니다([파일 비밀 값 암호화](#파일-비밀-값-암호화-authfilekey)). PROD는 키를 요구합니다 | — |
| `KeySource.signingKid()`, `KeySource.keys()` | default 메서드. 앱 구현은 `signingKid()`로 kid를 정하거나 `keys()`로 세트 전체를 줍니다. 람다 `KeySource { ... }`는 키 하나에 유도 kid입니다 | — |

PROD `build()`는 세트의 모든 키에 비밀키 강도 규칙을 적용합니다(약한 검증 키는 `verification key <kid>: ...`로 보고). 정한 것: 회전 팩터리는 `KeySources.rotating(JwtKeys)`로, kid 메서드는 `KeySource.signingKid()`/`keys()`로 고정했습니다. auth는 새 토큰을 서명 키로 서명하고, 토큰은 그 헤더의 kid가 가리키는 세트의 키로 검증합니다.

```java
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.AuthFileKey;
import com.snoworca.naruserve.utils.auth.KeySources;
import com.snoworca.naruserve.utils.auth.jwt.JwtKeys;
import java.io.File;
import java.nio.charset.StandardCharsets;

JwtKeys keys = JwtKeys.builder()
    .signingKey("k2", "a-new-32-byte-or-longer-signing-key!".getBytes(StandardCharsets.UTF_8))
    .verificationKey("k1", "the-old-32-byte-or-longer-signing-key".getBytes(StandardCharsets.UTF_8))
    .build();
Auth rotating = Auth.builder(KeySources.rotating(keys)).build();
Auth fromFile = Auth.builder(KeySources.fromFile(new File("/var/lib/app/naru-auth.key"), AuthFileKey.fromEnv())).build();
```

```kotlin
val keys = JwtKeys.builder()
    .signingKey("k2", "a-new-32-byte-or-longer-signing-key!".toByteArray())
    .verificationKey("k1", "the-old-32-byte-or-longer-signing-key".toByteArray())
    .build()
val auth = Auth.builder(KeySources.rotating(keys)).build()
val vault = KeySource { "a-key-read-from-the-vault-32-bytes!!".toByteArray() }
```

## auth 오류 레이어: auth.errors(), auth.errorMiddleware()

| API | 설명 | Express 대응 |
|---|---|---|
| `auth.errors()` | Core `ErrorHandler`. 라우트 뒤 **마지막**에 `app.onError(auth.errors())`. 앱 핸들러가 던진 `DuplicateUserException` 409 `duplicate_user`, `AuthUnavailableException` 503(그 코드, 예: `security_write_conflict`), auth의 `IllegalArgumentException` 하위 클래스 400 `BAD_REQUEST`, `AuthRejection` 그 상태·코드(+ `Retry-After`), `AuthStateException` 500 `INTERNAL_ERROR`(메시지는 본문에 넣지 않고 로그에만. Core가 `request <id> failed` ERROR로 한 번 남기고 그 스택 트레이스에 `AuthStateException` 메시지가 실리며 auth는 따로 줄을 남기지 않으므로, 공개 경로의 `auth.user(req)`는 `auth.userOrNull(req)`를 적은 ERROR 하나)를 auth JSON으로 바꿉니다. 그 밖의 예외(앱의 `RuntimeException`, auth가 아닌 `IllegalArgumentException`)는 응답하지 않고 같은 인스턴스 그대로 다음 오류 층에 넘깁니다. PROD에서는 auth가 기동할 때 덧붙이는 마지막 층이 그것을 JSON으로 답합니다([오류 응답](#오류-응답과-authresponses) 참고). `Auth`마다 같은 인스턴스 | 맨 끝의 `app.use((err, req, res, next) => ...)` |
| `auth.errorMiddleware()` | 같은 변환을 하는 `ErrorMiddleware`. Core가 기동 뒤에도 받는 `app.use(...)`로 붙입니다(기동 뒤 `onError`는 Core가 거부). 기동 뒤에 라우트 묶음을 더할 때마다 그 뒤에 다시 붙입니다. 다른 예외는 `next.error(err)`. 같은 인스턴스 | 같음 |

마지막 ROUTE 뒤에 이 둘 중 하나(바로 그 객체)가 없으면 doctor WARN이고(앱 자신의 `onError`가 그 자리에 있어도 WARN), auth가 기동할 때 라우트 뒤, 앱 자신의 오류 처리기 다음에 `auth.errorMiddleware()`를 덧붙입니다. 기동 뒤에 더한 라우트는 요청 때 확인하며, 이때도 WARN을 남깁니다: 등록이 바뀐 뒤 첫 요청에서 다시 확인하고, 1분에 한 번까지만 남깁니다. 이미 마운트한 Router에 나중에 더한 라우트는 그 마운트 위치에 있는 것으로 셉니다. auth 라우터(`auth.routes()`, `auth.adminRoutes()`)는 자기 오류 미들웨어가 있어 세지 않습니다.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).build();
auth.install(app);
app.post("/users", (req, res) -> res.status(201).send(auth.users().create("neo", "pw-pw-pw-pw".toCharArray()).getId()));
app.onError(auth.errors());                 // 라우트 뒤 마지막
app.get("/late", (req, res) -> res.send("late"));   // 기동 뒤에 더한 묶음
app.use(auth.errorMiddleware());            // 묶음마다 다시
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).build()
auth.install(app)
app.post("/users") { _, res -> res.status(201).send(auth.users().create("neo", "pw-pw-pw-pw".toCharArray()).id.orEmpty()) }
app.onError(auth.errors())
app.get("/late") { _, res -> res.send("late") }
app.use(auth.errorMiddleware())
```

## 첫 계정: .user(...)

`.user(name, @Nullable password, roles...)`의 비밀번호는 `build()`에서 한 번 해시합니다. `onStart`에서 doctor가 통과한 뒤, 그 이름의 계정이 **없을 때만** 만들고(`onUserCreated` 한 번, 출처 `UserOrigin.BOOTSTRAP`, before 이벤트 없음), 있으면 아무것도 바꾸지 않고 INFO를 남깁니다. **`.user`는 이미 있는 계정의 비밀번호를 절대 바꾸지 않습니다.** 환경 변수의 비밀번호를 바꿔도 그대로입니다. 다른 노드가 먼저 만들어 `create`가 `DuplicateUserException`을 던지면 INFO(`already exists`)를 남기고 기동을 계속합니다.

비밀번호가 null이면: 계정이 이미 있으면 무시합니다(INFO만, doctor FAIL도 setup 토큰도 없음). 없으면 기동 때 doctor가 봅니다. admin 역할(내장 `admin`이나 `*`를 주는 `RolePolicy` 역할. DB `RoleStore` 역할과 `.replaceBuiltinRoles()`로 바뀐 내장 역할은 세지 않음)을 가진 `.user` **하나**만 비밀번호를 비울 수 있고, 그 밖(관리자 아닌 역할, 둘 이상)은 FAIL입니다(DEV 포함). 통과하면 DEV는 임시 비밀번호를 만들어 한 번 WARN으로 찍고 계정을 만듭니다. PROD는 `.recovery(true)`일 때만 일회용 setup 토큰을 WARN으로 찍고 기동하며, recovery가 꺼져 있으면 doctor FAIL입니다. setup 토큰은 `POST /auth/setup`으로 씁니다([setup과 복구](#계정-엔드포인트-authroutes)). `.recoveryFromEnv()`는 `NARU_AUTH_RECOVERY`를 읽습니다. Express 대응: 기동 때 도는 시드 스크립트.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .user("admin", System.getenv("NARU_ADMIN_PASSWORD"), "admin")   // 없을 때만 만든다
    .roles(RolePolicy.builder().role("editor", "posts:read").build())
    .user("bob", System.getenv("BOB_PASSWORD"), "editor")
    .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .user("admin", System.getenv("NARU_ADMIN_PASSWORD"), "admin")
    .recovery(false)
    .build()
```

## 자격 증명 읽기와 auth 본문

| 전송 모드 | auth 미들웨어가 읽는 access 자격 증명 |
|---|---|
| `HEADER` (`.transport(...)`를 부르지 않을 때) | `Authorization: Bearer <token>`만. `naru_at` 쿠키는 읽지 않아 없는 것과 같습니다 |
| `COOKIE` | `naru_at` 쿠키만. `Bearer`는 읽지 않아 없는 것과 같습니다 |
| `BOTH` | 둘 다. 함께 오면 여러 자격 증명입니다 |

`Authorization` 헤더 둘 이상(어느 모드든, 스킴과 무관), `naru_at` 쿠키 둘 이상, `BOTH`의 Bearer와 쿠키는 **여러 자격 증명**입니다: 보호 경로와 자격 증명 엔드포인트에서 400 `invalid_request`, 공개 경로에서는 익명입니다. 익명 진입 엔드포인트 다섯(`login`, `register`, `setup`, `refresh`, `logout`)은 겹친 `naru_at`을 쿠키가 없는 것으로 봅니다(`Authorization` 둘은 그래도 400). refresh 쿠키 `naru_rt`는 access 자격 증명이 아닙니다. API 키를 켜지 않으면 다른 스킴의 `Authorization`(예: `ApiKey ...`)은 자격 증명이 아닙니다.

공개 경로(`.permit`, `.permitPrefix`)에서는 유효한 토큰이면 주체를 붙이고(`auth.userOrNull(req)`), 무효·만료·여러 자격 증명이면 익명으로 핸들러가 돌며 응답은 200 그대로입니다. 보안 연결 검사(보안 연결, 로컬 요청, DEV, `.allowInsecureTransport`)를 통과하지 못한 PROD 요청은 공개 경로에서 토큰을 읽지 않습니다. 자격 증명 엔드포인트는 `.permit`으로도 공개되지 않습니다.

auth 엔드포인트에 본문이 있는 POST(`Content-Length`가 0보다 크거나 `Transfer-Encoding`이 있음)는 Core `req.body`와 같은 JSON 미디어 타입 검사(`application/json` 또는 `application/*+json`, charset은 없거나 UTF-8)를 통과해야 하고, 아니면 본문을 읽기 전에 415 `UNSUPPORTED_MEDIA_TYPE`입니다. 본문을 쓰지 않는 logout도 같습니다. 본문 없는 refresh·logout은 `Content-Type`을 보지 않습니다. 정한 것: 이 검사는 auth 라우터의 미들웨어가 아니라 전역 auth 미들웨어가 하고, 415 JSON을 직접 답합니다(`HttpException`을 던지지 않음). 익명 진입 엔드포인트에서는 보안 연결과 `Authorization` 중복 검사 바로 뒤에, 그 밖의 auth 엔드포인트(`/auth/password`, 관리 POST)에서는 인증 뒤에 돕니다(자격 증명이 없으면 먼저 401). 그래서 `app.registrations()`의 auth 라우터 모양이 그대로이고, 라우터의 어떤 처리기보다도 먼저 돕니다.

Express 대응: `express-jwt`의 `getToken`(헤더 또는 `cookie-parser`의 `req.cookies`)과 `express.json()`의 타입 검사.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .transport(TokenTransport.COOKIE)   // naru_at 쿠키만 읽음
    .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).transport(TokenTransport.BOTH).build()
```

## 로그인 제한 저장소: LoginThrottle

| API | 설명 | Express 대응 |
|---|---|---|
| `spi.LoginThrottle` | `long retryAfter(key)`(0이면 허용, 양수면 남은 차단 밀리초), `long recordFailure(key)`(새 차단 밀리초, 아직이면 0), `void reset(key)`. default `retryAfter(key, max, windowMillis)`·`recordFailure(key, max, windowMillis)`는 한 인자 메서드로 넘기고(구현 자신의 한도), default `boolean usesLimitArguments()`는 false입니다. 모두 동기·`throws Exception`. 0.11.0부터는 default 메서드만 더합니다 | `rate-limiter-flexible`의 store |
| `spi.ForwardingLoginThrottle` | 데코레이터의 기반 공개 추상 클래스. default와 `usesLimitArguments()`까지 모든 메서드를 감싼 throttle로 넘기므로, 일부만 재정의해도 선언이 거짓이 되지 않습니다 | — |
| `InMemoryLoginThrottle` | 메모리 구현(재시작하면 사라짐). 한도 인자를 지키고(`usesLimitArguments()` 참), 한 인자 메서드는 15분 5회(`builder().limits(max, windowMillis)`). 키 수 상한은 JVM 100,000·Android 10,000(`builder().maxKeys(n)`)이고, 차면 가장 오래 실패한 키를 버리며 처음 한 번만 WARN. `builder().timeSource(...)`, `builder().logger(...)` | `express-rate-limit`의 MemoryStore |

빌더 `.loginThrottle(throttle)`로 auth가 쓰는 throttle을 정합니다. 부르지 않으면 사용자 저장소를 따릅니다(`FileUserStore`면 그 옆의 스냅숏 파일, 그 밖에는 메모리). 여러 노드라면 앱이 구현한 공유 저장소를 지정합니다([자체 데이터베이스 연결](#자체-데이터베이스-연결)). `MULTI_INSTANCE`는 메모리 throttle과 기본값으로 떨어지는 경우를 `build()`에서 거부합니다. auth는 비밀번호 로그인 실패와 API 키 실패를 이것으로 셉니다(API 키는 한도 인자 메서드만 부르고 성공해도 `reset`하지 않습니다).

**로그인 제한**: `POST /auth/login`은 정규화한 로그인 이름(NFKC 뒤 `toLowerCase(Locale.ROOT)`)으로 세 키를 셉니다. 없는 이름도 똑같이 세므로 429로 계정이 있는지 드러나지 않습니다. `(사용자, IP)`는 15분에 5번, IP는 15분에 20번(유효한 신뢰 기기 토큰을 든 요청은 면제), 사용자 전용은 15분에 10번(신뢰 기기가 없는 요청만)입니다. IP는 프록시 신뢰를 거친 `req.remoteAddress()`이고 IPv6는 `/64`가 한 키입니다. 넘으면 해시 없이 곧바로 429 `too_many_attempts` + `Retry-After`(초)이며 잠자며 기다리지 않습니다. 비밀번호 로그인에만 적용하므로 서비스 계정 이름의 로그인 실패는 그 계정의 API 키 인증을 막지 않습니다. 빌더 `.loginFailureLimits(perUserAndIp, perIp, perUser, windowMillis)`가 세 한도와 창을 함께 바꿉니다(각 한도 1 이상, 창 1초 이상. 아니면 `build()`가 `AuthConfigException`). Express 대응: `rate-limiter-flexible`의 "로그인 무차별 대입 방지" 예제(이름+IP, IP 두 limiter).

**파일 기본값**: `FileUserStore`를 쓰고 `.loginThrottle(..)`·`.deviceTrust(..)`를 부르지 않으면 같은 디렉터리에 `<이름>-login-throttle.json`과 `<이름>-device-trust.json`(`users.json`이면 `users-login-throttle.json`, `users-device-trust.json`)을 씁니다. 이 두 저장소는 `FileLoginThrottle`과 `FileDeviceTrust`이며 공개 생성자가 없는 `utils.auth`의 package-private 클래스라 앱은 이 방법으로만 씁니다(auth가 `(File, TimeSource, Logger, AuthFileKey)`로 만들고, 생성자가 파일을 읽으므로 깨진 파일이면 `build()`가 실패). 파일은 저장소가 쓸 것이 있을 때, 그것도 바뀐 것이 있을 때만 생깁니다. throttle 파일은 60초마다 쓰는 스냅숏과 멈출 때, 신뢰 기기 파일은 첫 무효 표시나 스냅숏 때 생기므로 새로 설치하면 처음에는 `users.json`과 `users-refresh-tokens.json`만 보입니다. 로그인 실패 카운터와 신뢰 기기 상태(실패 횟수, 무효 표시)를 그 파일에 두어 재시작한 뒤에도 남깁니다. throttle은 마지막 스냅숏을 다시 읽고(비정상 종료면 그 스냅숏 뒤의 실패는 잃을 수 있음), 기기 무효 표시는 실패한 로그인에 답하기 전에 디스크에 씁니다. 로그인 제한 카운터는 실패한 로그인마다 쓰지 않고 60초마다 바뀐 것이 있을 때 한 번 스냅숏으로 씁니다(멈출 때도 한 번). 키는 SHA-256(base64url)으로만 두므로 메모리와 파일 어디에도 로그인 이름이나 IP가 남지 않고, 마지막 실패가 24시간(설정한 창이 더 길면 그 창)보다 오래된 키는 10분 정리 작업이 지웁니다. 이 정리는 메모리 기본값에도 적용되고, `.loginThrottle(..)`로 준 throttle은 메모리든 앱이 구현한 것이든 건드리지 않습니다(앱 저장소의 오래된 행은 앱이 지웁니다). 기기 신뢰 상태는 메모리 기본값과 파일 기본값 모두 로그인 제한처럼 토큰 수 상한(JVM 100,000·Android 10,000)이 있고, 차면 만료한 것, 그다음 무효가 아닌 것 중 창이 가장 오래된 것을 버리며 처음 한 번만 WARN입니다. 모든 항목이 무효 표시일 때만 가장 먼저 만료하는 무효 표시를 버립니다. 기기 토큰 무효 표시는 드물고 보안에 중요하므로 그 실패를 돌려주기 전에 곧바로 씁니다. 형식은 `{"format":"naru-login-throttle","version":1,"keys":[...]}`, `{"format":"naru-device-trust","version":1,"tokens":[...]}`이고 `AtomicFiles` 규약으로 씁니다. 모르는 버전이나 깨진 파일이면 그 버전을 적어 `build()`가 실패하고 파일은 덮어쓰지 않습니다. 한 `FileUserStore`로 만든 `Auth`는 모두 같은 두 저장소를 씁니다. 이 공유 저장소들(refresh 기본값 포함)은 그 `FileUserStore`로 마지막에 `build()`하거나 `install(app)`한 `Auth`의 `.timeSource`와 로거(`.logger`가 없으면 그 앱의 로거)를 씁니다. 그래서 먼저 만든 `Auth`의 시계나 이미 멈춘 앱의 로거에 묶이지 않습니다. 두 기본값도 한도 인자를 지킵니다(`usesLimitArguments()` 참).

```java
LoginThrottle counted = new ForwardingLoginThrottle(new InMemoryLoginThrottle()) {
    @Override
    public long recordFailure(String key, int max, long windowMillis) throws Exception {
        System.out.println("failed: " + key);
        return super.recordFailure(key, max, windowMillis);
    }
};
long blockedFor = counted.retryAfter("alice|203.0.113.7", 5, 15 * 60_000L);
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
        .loginThrottle(counted)
        .loginFailureLimits(3, 20, 10, 15 * 60_000L)
        .build();
```

```kotlin
val counted = object : ForwardingLoginThrottle(InMemoryLoginThrottle()) {
    override fun recordFailure(key: String, max: Int, windowMillis: Long): Long {
        println("failed: $key")
        return super.recordFailure(key, max, windowMillis)
    }
}
val blockedFor = counted.retryAfter("alice|203.0.113.7", 5, 15 * 60_000L)
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .loginThrottle(counted)
    .loginFailureLimits(3, 20, 10, 15 * 60_000L)
    .build()
```

## 독립 JWT: Jwt, JwtClaims, JwtException

`utils.auth.jwt`는 `Auth` 없이 HS256 JSON Web Token을 서명하고 검증합니다(기기 짝짓기, 서명한 링크). Express 대응: `jsonwebtoken`의 `jwt.sign(payload, key)` / `jwt.verify(token, key, { algorithms: ['HS256'] })`.

| API | 뜻 |
|---|---|
| `Jwt.builder(JwtKeys)` → `Jwt.Builder` | `issuer(@Nullable String)`, `audience(@Nullable String)`(값이 없는 토큰에 쓰고 검증에서 요구), `leeway(long millis)`(기본 60초. 0 미만이나 5분 초과는 `IllegalArgumentException`), `maxTokenBytes(int)`(기본 8 KiB), `timeSource(TimeSource)`, `build()` |
| `String sign(JwtClaims)` | 헤더는 정확히 `alg`(`HS256`), `typ`(claims의 타입, 없으면 `JWT`), `kid`(세트의 서명 kid). `iat`는 주지 않으면 지금. 크기 한도를 넘는 토큰은 `IllegalArgumentException` |
| `JwtClaims verify(token)`, `JwtClaims verify(token, @Nullable expectedType)` | 순서: 크기, 정규형 base64url 세 부분, `alg`가 정확히 `HS256`(헤더로 고르지 않음)이고 `crit`가 없는 엄격 JSON 헤더, `kid`의 키, 상수 시간 서명 비교, 그다음 claims: `exp` 필수·정수, `nbf`/`iat`가 leeway보다 미래가 아님, `expectedType`을 주면 `typ` 일치, 설정한 issuer와 audience. `kid`가 없는 토큰은 세트에 키가 하나일 때만 받습니다. `jku`·`jwk`·`x5u`는 무시하고 가져오지 않습니다 |
| `JwtClaims.builder()` → `JwtClaims.Builder` | `subject`, `type`, `issuer`, `audience(String...)`, `issuedAt`, `notBefore`, `expiresAt`(필수), `tokenId`, `claim(name, String / long / boolean / List<String>)`. getter: `getSubject()`, `getType()`, `getIssuer()`, `getAudience()`, `getIssuedAt()`, `getNotBefore()`, `getExpiresAt()`, `getTokenId()`, `getString(name)`, `getLong(name)`, `getBoolean(name)`, `getStringList(name)`, `getClaimNames()` |
| `JwtException`(checked) | `getReason()`: `JwtException.Reason` `MALFORMED`, `BAD_ALGORITHM`, `UNKNOWN_KEY`, `BAD_SIGNATURE`, `EXPIRED`, `NOT_YET_VALID`, `WRONG_TYPE`, `WRONG_ISSUER`, `WRONG_AUDIENCE`, `TOO_LARGE`. 메시지에 토큰이나 키는 없습니다 |

시각은 다른 auth 시각처럼 epoch 밀리초입니다. 토큰은 초 단위로 담으므로 서명할 때 초 아래는 버립니다. auth 자신의 access 토큰도 이런 JWT이고 헤더 `typ`은 `at+jwt`, claims는 `sub`, `ver`, `fam`, `iat`, `exp`, `jti`입니다. access 토큰이 검증되지 않는 보호 요청은 401 `invalid_token`입니다.

```java
Jwt jwt = Jwt.builder(JwtKeys.of("k1", "0123456789abcdef0123456789abcdef".getBytes())).issuer("hub").build();
String t = jwt.sign(JwtClaims.builder().subject("device-7").type("device")
        .expiresAt(System.currentTimeMillis() + 3_600_000L).build());
try {
    JwtClaims c = jwt.verify(t, "device");
    System.out.println(c.getSubject());
} catch (JwtException e) {
    System.out.println("refused: " + e.getReason());
}
```

```kotlin
// `key`(32바이트 이상의 ByteArray)와 `now`(System.currentTimeMillis())는 위 Java 블록과 같습니다
val jwt = Jwt.builder(JwtKeys.of("k1", key)).issuer("hub").build()
val t = jwt.sign(JwtClaims.builder().subject("device-7").type("device").expiresAt(now + 3_600_000L).build())
val subject: String? = jwt.verify(t, "device").subject
```

## 보호 범위: protectOnly와 upgrade

`.protectOnly(prefix...)`를 주면 기본 거부는 나열한 접두사 아래와 auth 마운트(마운트 경로, 기본 `/auth`. 관리 라우트가 그 안에 있습니다) 아래에만 걸립니다. 범위는 라우터보다 넓게 정규화한 경로로 판정합니다: 퍼센트 디코딩, 소문자, 겹친 슬래시 합침. `.`이나 `..` 세그먼트, 디코딩할 수 없는 이스케이프는 보호로 셉니다. 범위 밖 경로는 `.permitPrefix` 경로처럼 선택 인증입니다. 유효한 토큰이면 주체가 붙고, 잘못되거나 만료된 토큰이면 익명으로 남으며, 그곳의 가드는 주체가 없으면 여전히 401로 답합니다.

- 프로토콜 upgrade는 `.permit`·`.protectOnly`와 상관없이 늘 보호됩니다: `Upgrade` 헤더가 있거나, 어느 `Connection` 헤더 줄에든 `upgrade` 토큰이 있으면(WebSocket이든 h2c든) 그렇습니다. `.permitWebSocket(path)`는 정확히 그 경로(디코딩한 WebSocket `req.path()`와 비교)로 오는 WebSocket upgrade만 엽니다.
- `.protectOnly` 아래에서 경로가 정규식이나 배열인 등록은 판정할 수 없습니다. 시작 전이면 doctor FAIL(`protectOnly range`), 시작 뒤면 그 등록 호출(`app.get(Pattern, ...)`, 마운트된 라우터의 라우트, `app.use(path, router)`)이 `IllegalStateException`을 던지고 아무것도 서비스하지 않습니다. `.protectOnly`가 없으면 아무것도 거부하지 않습니다.
- doctor는 범위 밖 라우트와 WebSocket 라우트마다 WARN을 한 번 내고, 기동 로그에 `protected: /api, /auth`(마운트 경로를 바꾸면 `/auth` 대신 그 경로)를 남깁니다.

Express 대응: `app.use('/api', expressjwt(...))`처럼 한 접두사에만 건 인증 미들웨어.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).protectOnly("/api").permitWebSocket("/live").build();
auth.install(app);
app.staticFiles("/", new File("dist"));   // 공개: 범위 밖
app.get("/api/users", (req, res) -> res.json(Collections.emptyList()));   // 보호
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).protectOnly("/api").permitWebSocket("/live").build()
```

## describe()와 기동 로그

`auth.describe()`는 실제 설정을 사람이 읽는 여러 줄 요약으로 돌려줍니다: 모드, 프로필, 배포, 전송, 마운트·관리 경로, 메서드를 붙인 공개 경로, 보호 범위(`protected: ...`), `.permitWebSocket`으로 연 WebSocket 경로, 사용자·refresh 토큰·API 키 저장소. 키·비밀번호·토큰은 담지 않습니다. 사람이 읽는 글이라 판마다 바뀔 수 있으니 파싱하지 마세요.

기동 로그도 남깁니다: `.user` 계정은 없을 때만 만들고 기존 계정이나 그 비밀번호를 덮어쓰지 않는다는 것, DEV 임시 서명 키는 재시작하면 사라진다는 것, `.recovery(true)`가 켜져 있으면 기동마다 WARN 한 번, `.allowInsecureTransport` 이유는 기동마다 WARN. 일회용 setup 토큰과 DEV 임시 비밀번호는 각각 기동 로그 한 줄에만 나오고 다른 곳에는 나오지 않습니다.

Express 대응: 기동할 때 `console.log`로 찍는 설정 요약.

```java
System.out.println(Auth.builder(System.getenv("JWT_SECRET")).protectOnly("/api").build().describe());
```

```kotlin
// `auth`는 위에서 만든 Auth입니다
println(auth.describe())
```

## 감사 이벤트: AuditEvent, AuditSink, AuditSinks

| API | 뜻 |
|---|---|
| `AuditSink`(`@FunctionalInterface`, `void record(AuditEvent) throws Exception`) | `.audit(sink)`로 정합니다. 요청 스레드에서 불립니다. sink가 던져도 응답은 바뀌지 않고 auth가 실패를 로그에 남깁니다 |
| `AuditEvent`(불변, final) | `getTimestampMillis()`, `getType()`, `getOutcome()`(`AuditEvent.Outcome` `SUCCESS`, `DENIED`, `FAILURE`), `@Nullable getActorId()`, `@Nullable getTargetId()`, `getAction()`, `@Nullable getSourceIp()`(`req.remoteAddress()`), `@Nullable getRequestId()`(`req.requestId()`), `@Nullable getReason()`, `getDetails()`(예: 키 행위자의 `apiKeyId`, `serviceAccount`). id만 담고 사용자 이름·이메일·비밀번호·토큰·쿠키 값·키 비밀·`Authorization` 값은 담지 않습니다. `AuditEvent.builder(type, action)` → `AuditEvent.Builder` |
| 종류 | `AuditEvent.RECOVERY_USED`(`recovery_used`), `GRANT_DENIED`, `SELF_CHANGE_DENIED`, `SECURITY_WRITE_CONFLICT`, `USER_UPDATE_FAILED` (`user_update_failed`), `API_KEY_ISSUED`, `API_KEY_REVOKED`. 늘어날 수 있습니다 |
| `AuditSinks.logger(Logger)`, `AuditSinks.all(AuditSink...)`, `AuditSinks.none()` | 이벤트마다 INFO 한 줄 / 모든 sink에 차례로(하나가 실패해도 나머지는 받습니다) / 모두 버림(기본값) |

auth 라우터 경로에서 store가 보안 쓰기를 반영하지 않아 503 `security_write_conflict`로 답하면 `security_write_conflict` 이벤트를 하나 냅니다. auth 밖에서 store 기본 메서드를 바로 부르면 내지 않습니다.

Express 대응: `winston` 같은 로거로 보안 이벤트를 남기는 앱 코드.

```java
import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.audit.AuditSinks;

Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
        .audit(AuditSinks.logger(new ConsoleLogger()))
        .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).audit { e -> println("${e.type} ${e.actorId}") }.build()
```

## refresh 토큰 저장소: RefreshTokenStore

`spi.RefreshTokenStore`(사용자 구현, 동기 `throws Exception`)는 refresh 토큰마다 `RefreshRecord` 하나를 두어 토큰을 한 번만 회전하고, 재사용을 알아내고, 계열을 폐기합니다: `void save(RefreshRecord)`, `@Nullable RefreshRecord find(tokenId)`, `boolean markUsed(tokenId, nowMillis)`(원자적: 쓰지 않았고 폐기되지 않은 기록 하나에 대한 동시 호출 중 정확히 하나만 true), `int revokeFamily(familyId)`, default `int purgeExpired(nowMillis)`(기본은 아무것도 지우지 않음). `RefreshRecord.builder()` → `RefreshRecord.Builder`: `tokenId`, `familyId`, `userId`, `tokenHash`, `createdAt`, `expiresAt`, `@Nullable usedAt`, `revoked`.

| 저장소 | 쓰이는 때 |
|---|---|
| `InMemoryRefreshTokenStore` | `InMemoryUserStore` 옆의 기본값이고, `SINGLE_INSTANCE`에서 다른 사용자 저장소 옆의 기본값(그때 doctor는 재시작하면 모든 사용자가 로그아웃된다고 WARN을 한 번 냅니다) |
| `FileRefreshTokenStore(File)` | `FileUserStore` 옆의 기본값: 같은 디렉터리의 `<이름>-refresh-tokens.json`(`users.json`이면 `users-refresh-tokens.json`). 한 `FileUserStore`로 만든 `Auth`는 모두 같은 저장소 하나를 씁니다. 형식은 `{"format":"naru-refresh-tokens","version":1,...}`. 모르는 버전이면 그 버전을 적어 `build()`가 실패하고 파일은 덮어쓰지 않습니다. 오래 이어지는 세션으로 파일이 커지지 않도록 한 계열은 현재 기록과 가장 최근에 쓴 기록 32개만 남기고, 그보다 오래된 사용한 기록은 다음 기록을 저장할 때 지웁니다. 남은 기록을 다시 내면 여전히 계열 전체를 폐기하지만, 그보다 오래된 토큰은 모르는 토큰처럼 401로만 거부합니다. 기록을 저장할 때 폐기된 기록(로그아웃했거나 재사용으로 폐기된 계열)과 이미 만료된 기록도 지웁니다. 어느 쪽이든 같은 401로 거부되므로, 로그인·로그아웃을 되풀이해도 파일이 커지지 않습니다. 파일이 담은 세션은 언제나 다시 refresh할 수 있습니다: 살아 있는 모든 계열이 한 번씩 더 회전할 자리가 한도 안에 없게 되면, 각 계열은 32개 대신 가장 최근에 쓴 기록 하나(현재 토큰의 바로 앞 토큰이고, 그것을 다시 내면 여전히 계열 전체를 폐기합니다)만 남기므로 회전에 새 바이트가 들지 않습니다. 새 로그인(로그인, 가입, setup, 비밀번호 변경처럼 새 계열을 시작하는 발급)은 그렇게 줄인 형태로도 새 계열을 포함한 모든 세션이 한 번씩 더 회전할 자리가 크기 한도(기본 8 MB) 안에 없거나, 파일이 한도의 마지막 8분의 1 안으로 커지게 되면, 세션이 만료되거나 로그아웃할 때까지 503으로 거부합니다. 기존 세션은 계속 refresh합니다. 한도는 `new FileRefreshTokenStore(file, maxFileBytes, logger)`로 올리거나, 앱이 구현한 `RefreshTokenStore`를 씁니다 |
| 앱이 구현한 `RefreshTokenStore` | `MULTI_INSTANCE`에서 노드끼리 공유하는 저장소. `.refreshTokens(..)`로 지정합니다([자체 데이터베이스 연결](#자체-데이터베이스-연결)) |

auth는 토큰을 발급·회전·폐기할 때와 청소가 만료 기록을 지울 때만 기록을 씁니다. 로그인 실패로는 쓰지 않습니다. 10분마다 데몬 스레드가 `purgeExpired(now)`를 부르고, `.apiKeys(store)`가 있으면 같은 회차에 `ApiKeyStore.deleteExpired(now)`도 부릅니다. `onStop`에서 멈춥니다.

Express 대응: `express-session`의 세션 store(`connect-redis`, `connect-pg-simple`).

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
        .users(new FileUserStore(new File("data/users.json"), AuthFileKey.fromEnv()))   // users-refresh-tokens.json이 그 옆에 생깁니다
        .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).refreshTokens(InMemoryRefreshTokenStore()).build()
```

## API 키 저장소: ApiKeyStore와 키 타입

`apikey.ApiKeyStore`(사용자 구현, 동기 `throws Exception`, 인자는 null이 아님)는 0.11.0부터 고정되어 있습니다: `@Nullable findByPrefix(prefix)`, `@Nullable findById(keyId)`, `listByOwner(ownerUserId)`(죽은 키 포함), `insertIfUnderLimit(StoredApiKey, int maxLiveKeys, long nowMillis)`(소유자의 살아 있는 키, 즉 만료 전이고 ownerVer가 새 키 이상인 키가 한도 미만일 때만. 이미 있는 prefix는 `ApiKeyPrefixCollisionException`), `deleteByIdAndOwner`, `deleteById`, `deleteAllByOwner`, `deleteStaleByOwner(owner, v)`(ownerVer < v), `deleteExpired(nowMillis)`, 그리고 default `deleteFutureByOwner(owner, v)`(ownerVer > v, `listByOwner`와 `deleteById`로), `updateLastUsed(keyId, lastUsedAtMillis)`(아무것도 안 함), `supportsAtomicLimitInsert()`(false), `countKeysWithAdminScopes()`(-1: 셀 수 없음).

| 타입 | 뜻 |
|---|---|
| `StoredApiKey` | store 행. 비밀 해시를 가진 유일한 타입: `builder()` / `toBuilder()` → `StoredApiKey.Builder`. id, prefix, secretHash(소문자 16진 64자), ownerUserId, ownerVer(`long`), name, scopes, createdAt은 필수(빠지면 `build()`가 그 이름을 적어 `IllegalStateException`)이고 expiresAt, lastUsedAt, issuedByUserId는 `@Nullable`. `toString()`은 `nk_<prefix>_***`만 보이고 해시는 보이지 않습니다 |
| `ApiKey` | 비밀도 해시도 없는 앱용 사본: `ApiKey.of(stored, status)`, `getCreatedAt()`(`long`), `@Nullable getExpiresAt()`, `@Nullable getLastUsedAt()`, `@Nullable getIssuedByUserId()`, `getStatus()` |
| `IssuedApiKey` | `getKey()`와 이번 한 번뿐인 `getPlainKey()`(`nk_<prefix8>_<secret32>`). `toString()`은 `nk_<prefix8>_***` |
| `ApiKeyStatus` | `ACTIVE`, `EXPIRED`, `STALE`. 소유자의 `ver`를 캐시가 아닌 저장소에서 읽어 정하고, ownerVer가 그 `ver`보다 큰 키도 `STALE`입니다. 값이 늘 수 있으니 Kotlin `when`에는 `else`를 둡니다. `MULTI_INSTANCE`에서 ownerVer가 읽은 ver보다 큰 stale 키는 `maxKeysPerUser`에 계속 셉니다(늦은 복제본 읽기가 유효한 키를 가릴 수 있음). ownerVer가 ver보다 작은 stale 키는 세지 않습니다. auth 엔드포인트는 `active`·`expired`·`stale`로 쓰고, 앱 자신의 코덱은 enum 그대로 씁니다(Gson: `ACTIVE`) |

로그에서 키는 prefix로만 가리킵니다. `.apiKeys(store)`는 키를 켭니다: 사용자를 지우면 소속과 행 사이에 그 키를 지우고, 청소가 만료된 키를 지웁니다. 요청의 키 인증은 [API 키 인증](#api-키-인증)에, 앱 파사드 `auth.apiKeys()`와 `InMemoryApiKeyStore`는 [API 키 발급: auth.apiKeys()](#api-키-발급-authapikeys)에 있습니다.

Express 대응: `passport-headerapikey`의 verify 콜백이 읽는 앱의 API 키 테이블.

```java
StoredApiKey row = StoredApiKey.builder().id("k1").prefix("Ab3dE5gH")
        .secretHash("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
        .ownerUserId("42").ownerVer(0L).name("ci-bot").scopes(Collections.singleton("posts:read"))
        .createdAt(System.currentTimeMillis()).build();
ApiKey shown = ApiKey.of(row, ApiKeyStatus.ACTIVE);   // 해시 없음
```

```kotlin
// `row`: 위 Java 블록에서 만든 StoredApiKey
val expires: Long? = ApiKey.of(row, ApiKeyStatus.ACTIVE).expiresAt
```

## auth 쿠키

`COOKIE`·`BOTH` 모드에서 auth는 `naru_at`(access 토큰: HttpOnly, Path=/), `naru_rt`(refresh 토큰: HttpOnly, Path = 마운트 경로, 기본 `/auth`. refresh와 logout만 읽고 access 자격 증명으로는 절대 쓰지 않음), `naru_csrf`(스크립트가 읽음, Path=/), `naru_device`를 씁니다. 모두 SameSite=Strict이고 요청이 보안 연결일 때만 Secure입니다. 그 모드에서는 `.mountPath("/")`를 거부하고, `naru_rt`가 둘 이상이면 없는 것으로 셉니다. 쿠키는 심을 때와 같은 Path와 Domain으로 지웁니다. `.cookies(c -> c.domain("example.com"))`로 공통 속성을 줍니다. `.cookies()`로 Path나 Domain을 바꾸면 앱이 `refreshTtl` 동안 옛 Path와 Domain에 `Max-Age=0`을 보내야 합니다. auth는 옛 쿠키를 알아내거나 지울 수 없기 때문입니다. 쿠키 값은 로그·예외 메시지·감사 이벤트에 들어가지 않습니다.

Express 대응: `res.cookie(name, value, { httpOnly: true, sameSite: 'strict', secure: true, domain })`의 옵션.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .transport(TokenTransport.COOKIE)
    .cookies(c -> c.domain("example.com"))   // naru_at·naru_rt·naru_csrf·naru_device 공통
    .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .transport(TokenTransport.COOKIE)
    .cookies { c -> c.domain("example.com") }
    .build()
```

## 관리 쓰기의 권한 부여 규칙

관리 쓰기는 모두 행위자와 대상을 store에서 다시 읽고, 대상이 새로 얻는 스코프를 행위자의 유효 스코프가 모두 덮고, 수정·비밀번호 재설정·비활성화·삭제·멤버 제거·역할 제거라면 대상의 지금 스코프도 덮을 때만 허용합니다. 아니면 403 `insufficient_scope`입니다. 자기 역할이나 그룹을 바꾸거나 자기를 비활성화·삭제하는 행위자도 403 `insufficient_scope`로 거부하고, 행위자를 행위자이자 대상으로 적은 `self_change_denied` 감사 이벤트를 남깁니다. 앱의 경로(`auth.users()`)는 신뢰하므로 검사하지 않습니다. 아래 관리 엔드포인트가 모든 쓰기에 이 규칙을 씁니다.

## 토큰: TokenService와 TokenPair

`auth.tokens()`는 이 auth의 `TokenService`입니다. `issue(user)`는 새 refresh 계열(family)로 access·refresh 토큰 한 쌍(`TokenPair`)을 만들고 refresh 기록을 저장합니다(로그인, 가입, setup, 비밀번호 변경이 이 경로입니다). `refresh(refreshToken)`은 refresh 토큰, 사용자의 지금 `ver`와 `enabled`, 저장된 기록을 확인하고, 제시한 기록을 사용됨으로 표시(원자적 compare-and-set)한 뒤 같은 계열의 새 쌍을 줍니다. 이미 쓴 refresh 토큰을 다시 내면 그 계열 전체를 폐기합니다(재사용 감지, RFC 9700 4.14). 거부는 모두 `AuthRejection(401, "invalid_refresh_token")`이고 아무것도 발급하지 않습니다. `verifyAccess(token)`은 access 토큰의 클레임(`JwtClaims`)을 주고, 무효면 401 `invalid_token`입니다. store 실패는 `AuthUnavailableException`(503)입니다. Express 대응: passport-jwt 로그인 라우트의 `jwt.sign` 호출과 refresh 토큰 테이블.

| 토큰 | 헤더 | 클레임 | 서명 키 |
|---|---|---|---|
| access | `alg` HS256, `typ` `at+jwt`, `kid` | 정확히 `sub`, `ver`, `fam`, `iat`, `exp`, `jti`(scope·roles 클레임 없음: 스코프는 요청마다 읽음) | HMAC(그 kid의 비밀, `"naru-access"`) |
| refresh | `typ` `rt+jwt` | `sub`, `ver`, `fam`, `jti`, `iat`, `exp` | HMAC(그 kid의 비밀, `"naru-refresh"`) |
| 기기 토큰 / 기기 이름 지문 | — | — | HMAC(비밀, `"naru-device"`) / HMAC(비밀, `"naru-device-fp"`) |

목적마다 키가 달라서 `typ`을 위조해도 한 목적의 토큰이 다른 목적으로 검증되지 않습니다. 키는 키 세트의 모든 kid(검증 전용 kid 포함)에서 파생하므로 회전 전에 서명한 토큰도 옛 kid로 검증됩니다. `TokenPair`: `getAccessToken()`, `getExpiresIn()`(초), `getRefreshToken()`, `getRefreshExpiresIn()`(초), `getTokenType()`(`"Bearer"`). JSON 필드 이름은 OAuth 2.0 토큰 응답의 camelCase(`accessToken`, `expiresIn`, `refreshToken`, `refreshExpiresIn`, `tokenType`)이고 `toString()`에는 토큰이 없습니다. 수명은 빌더 `.accessTtl(millis)`, `.refreshTtl(millis)`(1초 이상)로 정하고, 부르지 않으면 위 AuthProfile 표의 값입니다. 액세스 토큰은 60초의 시계 오차 허용(leeway, `Jwt.DEFAULT_LEEWAY_MILLIS`, `Auth`에서 바꿀 수 없음)으로 검증하므로 만료 뒤에도 60초까지 200으로 답할 수 있습니다. 만료를 시험하려면 시계를 `accessTtl`에 60초를 더한 시각 뒤로 옮기세요.

refresh 재사용 감지와 토큰 형식 교체(`TokenCodec`)는 아래 [refresh 재사용 감지와 TokenCodec](#refresh-재사용-감지와-tokencodec) 절에 있습니다.

ver는 늘기만 합니다. 모든 기기 로그아웃, 비밀번호 변경, 관리자 비밀번호 재설정, 비활성화, 복구가 `ver`를 정확히 1 올리고, 그 한 번으로 그 사용자의 access·refresh·기기 토큰, WebSocket 연결, API 키가 모두 무효가 됩니다. 역할·그룹·프로필 변경과 로그인 때의 rehash는 `ver`를 바꾸지 않고, 그 효과는 아래 주체 캐시의 TTL 안에 요청에 닿습니다. 비밀번호를 바꾸면 API 키를 다시 발급해야 합니다.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).accessTtl(10 * 60_000L).build();
AuthUser alice = auth.users().create("alice", "correct horse".toCharArray());
TokenPair pair = auth.tokens().issue(alice);
TokenPair next = auth.tokens().refresh(pair.getRefreshToken());   // 앞 refresh 토큰은 이제 사용됨
JwtClaims claims = auth.tokens().verifyAccess(next.getAccessToken());
```

```kotlin
// `auth`는 위에서 만든 Auth입니다
val alice = auth.users().create("alice", "correct horse".toCharArray())
val pair = auth.tokens().issue(alice)
val claims = auth.tokens().verifyAccess(pair.accessToken)
println("${claims.subject} expires in ${pair.expiresIn} s")
```

## 주체 캐시: userCacheTtl

auth 미들웨어는 사용자 행, 확장한 그룹, 역할, 스코프로 주체를 만들고 사용자 id마다 `userCacheTtl`(기본 5초. `.userCacheTtl(millis)`, 0이면 요청마다 store를 읽음) 동안 캐시합니다. 토큰의 `ver`나 `enabled`가 맞지 않으면 401 `invalid_token`입니다. auth를 거친 변경(관리 엔드포인트, `auth.users()`, 비밀번호 변경)은 그 노드에서 바로 보입니다. 비활성화·삭제·`ver` 증가는 그 사용자를, 역할·그룹 변경은 캐시 전체를 지웁니다. store에 직접 쓴 변경(다른 노드 포함)은 TTL 안에 보입니다. 관리 쓰기의 권한 부여 규칙은 이 캐시를 쓰지 않고 store를 다시 읽습니다. `.statelessAccess(true)`에서는 가드 없는 라우트가 store를 읽지 않는 최소 주체(`getId()`만 의미 있음. 이름 `""`, 빈 역할·스코프·속성, `isLoaded()` false)를 받고, 가드(`auth.require(...)` 등)는 캐시를 거쳐 승격한 뒤 `ver`·`enabled`를 확인합니다. 그래서 그런 라우트는 폐기를 access 토큰 수명이 지나서야 봅니다. doctor가 이것을 WARN으로 알립니다.

Express 대응: `deserializeUser` 결과를 몇 초 담아 두는 `lru-cache`.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .userCacheTtl(2_000)        // 기본 5000 ms, 0이면 요청마다 store
    .statelessAccess(true)      // 가드 없는 라우트는 getId()만 씀
    .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).userCacheTtl(0).statelessAccess(true).build()
app.get("/feed") { req, res -> res.send(auth.userOrNull(req)?.id ?: "anonymous") }
```

## WebSocket: auth.webSocket(handler)

`app.ws("/ws", auth.webSocket(handler))`는 핸들러를 감쌉니다. 인증과 Origin 판정은 101 전에 그대로 합니다(토큰 없는 upgrade는 401 JSON이고 핸들러는 실행되지 않음). 101 뒤에는 access 토큰으로 연 연결을 그 토큰의 `exp`에 `close(1008, "token expired")`로 닫습니다. 모든 연결은 `userCacheTtl`마다 주체를 다시 읽어, 사용자가 없거나 비활성이거나 `ver`가 다르거나 역할·스코프가 바뀌었으면 `close(1008, "access revoked")`로 닫습니다. auth가 그 노드에서 사용자를 비활성화·삭제하거나 비밀번호를 바꾸면 바로 닫습니다. API 키로 연 연결은 그 키의 `expiresAt`에 `close(1008, "api key expired")`로 닫고(만료 없는 키는 닫지 않음), 재확인 때마다 키 행도 다시 읽어 키가 지워졌거나 만료됐거나 stale이거나 ownerVer가 다시 읽은 `ver`보다 크거나 소유자에게 보류 표시가 있으면 `close(1008, "access revoked")`로 닫습니다(ownerVer가 큰 키는 `SINGLE_INSTANCE`에서 요청 경로 밖에서 지우고 `MULTI_INSTANCE`에서는 남김). 그 노드에서 auth가 키를 지우면(`auth.apiKeys().revoke`, 키 엔드포인트의 삭제) 그 키로 연 연결은 바로 닫힙니다. 다른 노드에서 지운 키는 다음 재확인, 즉 `userCacheTtl` 안에 닫힙니다. `.statelessAccess(true)`의 최소 주체는 첫 재확인에서 승격되며, 그것은 변경으로 치지 않습니다. 감싼 핸들러는 `auth.user(req)`로 주체를 읽습니다. doctor는 `WEBSOCKET` 등록 가운데 핸들러가 `auth.webSocket`으로 감싼 것이 아닌 등록이 하나라도 있으면 WARN을 내고(`auth.webSocket` 호출 수가 아니라 감싼 핸들러로 등록한 라우트 수를 셉니다. 등록하지 않은 래퍼는 세지 않고, 래퍼 하나를 두 라우트에 등록하면 둘 다 감싼 것입니다), `COOKIE`·`BOTH` 전송에 WebSocket 라우트가 있으면 Core WebSocket `allowedOrigins`가 와일드카드가 아닌지 확인하라는 WARN을 냅니다(Core에 그 설정을 읽는 공개 API가 아직 없어 FAIL은 내지 않습니다). Express 대응: 연결 때 토큰을 확인하고 만료 타이머를 거는 express-ws 핸들러.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).build();
auth.install(app);
app.ws("/ws", auth.webSocket((req, ws) -> ws.sendText("hi " + auth.user(req).getId())));
```

```kotlin
// `auth`는 위에서 만든 Auth입니다
app.ws("/ws", auth.webSocket { req, ws -> ws.sendText("hi " + auth.user(req).id) })
```

## 계정 잠금: Lockout

`.lockout(Lockout.after(5).forMinutes(15))`는 선택 기능인 하드 잠금입니다. 한 계정의 비밀번호 로그인이 그만큼 실패하면 그 시간 동안 비밀번호 로그인을 거부하고, 틀린 비밀번호와 똑같이 401 `invalid_credentials`로 답합니다(계정이 있는지, 잠겼는지 드러내지 않음). 비밀번호 로그인에만 적용하고 신뢰 기기 로그인과 API 키 인증은 잠그지 않습니다. 관리자는 `DELETE /auth/admin/users/:id/lockout`(204)으로 풉니다. 이 요청은 잠금과 그 사용자의 사용자 전용 로그인 제한 키만 지우고, IP 키와 (사용자, IP) 키는 그대로 둡니다. 공격자가 피해자를 일부러 잠글 수 있으므로 기본은 꺼져 있고, 기본 보호는 로그인 제한입니다. `POST /auth/login`이 실패를 세고(존재하는 계정의 비밀번호 실패마다 1), 잠긴 계정의 맞는 비밀번호도 해시 단계와 실패 응답 하한을 그대로 거쳐 같은 401로 답합니다. 성공한 로그인은 연속 실패 수를 지웁니다. 신뢰 기기 토큰을 든 로그인은 잠금을 보지 않습니다. Express 대응: `rate-limiter-flexible`의 "사용자 이름별 연속 실패" 예제.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .lockout(Lockout.after(5).forMinutes(15))   // 기본 꺼짐
    .build();
```

```kotlin
val lockout = Lockout.after(5).forMinutes(15)
val auth = Auth.builder(System.getenv("JWT_SECRET")).lockout(lockout).build()
println("${lockout.failures} failures, ${lockout.durationMillis} ms")
```

## API 키 사용 시각

키 인증이 성공하면 auth는 `ApiKeyStore.updateLastUsed(keyId, lastUsedAtMillis)`를 키마다 `TimeSource` 기준 1분에 한 번만, 요청 경로 밖의 백그라운드 스레드에서 부릅니다. 실패하거나 큐가 차면 WARN 로그만 남기고 요청에는 영향이 없습니다(`lastUsedAt`이 조금 오래될 뿐입니다). default는 아무것도 하지 않습니다. 키로 인증된 요청마다 이 기록기를 부릅니다.

## 관리 엔드포인트: auth.adminRoutes()

`auth.install(app)`이 `auth.adminRoutes()`를 마운트 경로 + `/admin`(기본 `/auth/admin`)에 마운트합니다. v1 관리 엔드포인트는 15개이고, `.apiKeys(store)`가 있으면 키 라우트 3개가 더해집니다. 아래 표의 이름 열은 엔드포인트의 짧은 이름입니다. A1–A6은 사용자 엔드포인트, G1–G9은 그룹 엔드포인트이고, 이 문서의 다른 곳에서도 이 이름으로 부릅니다(예: 관리 A2는 `POST /auth/admin/users`). 그룹 역할 라우트(G9 `PUT /groups/:id/roles`)는 v2라 등록하지 않습니다. 라우트마다 자기 스코프 가드가 있어 핸들러가 대상을 읽기 전에 403 `insufficient_scope`로 답하고(자격 증명이 없으면 401), 가드를 통과한 쓰기는 모두 위 권한 부여 규칙을 거칩니다. `auth-users:*`는 read와 write를, 내장 `admin`(`*`)은 모든 가드를 덮습니다. 본문은 앱의 JSON 코덱으로 읽습니다(`NaruServe`에서는 Gson). 사용자 본문은 해시가 없는 `{"id","username","enabled","roles","attributes","serviceAccount"}`입니다. Express 대응: 라우트마다 `requireScope(...)` 미들웨어를 단 관리용 `express.Router()`.

| 이름 | 메서드와 경로 | 스코프 | 성공 |
|---|---|---|---|
| A1 | `GET /users?offset=&limit=&search=` | `auth-users:read` | 200 `{"items","total","offset","limit"}`(limit 기본 50, 최대 200) |
| A2 | `POST /users` `{"username","password","roles","attributes","serviceAccount"}` | `auth-users:write` | 201 사용자 + `Location`. 빈 `password`(`""`)는 메시지 `password must be a non-empty JSON string`의 400 `BAD_REQUEST`. `serviceAccount`가 true인데 `password`가 있으면 400 `service_account_no_password`. API 키 행위자가 `password`를 보내거나 `"serviceAccount": true` 없이 만들면 그보다 먼저(스코프 가드보다도 먼저) 403 `api_key_not_allowed`; `RolePolicy`가 선언하지 않은 역할을 주면 WARN을 남깁니다 |
| A3 | `GET /users/:id` | `auth-users:read` | 200 사용자(`roles`는 사용자 자신의 역할이고, 그룹이 주는 역할은 `auth.user(req).getRoles()`에, 그룹 목록은 A6에 나옵니다) |
| A4 | `PATCH /users/:id` `{"username","password","enabled","roles","attributes"}` | `auth-users:write` | 200 사용자. 빈 `password`(`""`)는 메시지 `password must be a non-empty JSON string`의 400 `BAD_REQUEST`. 서비스 계정에 `password`를 주면 400 `service_account_no_password`. API 키 행위자가 `password`를 보내거나 키 자신의 소유자에게 `"enabled": true`를 보내면 그보다 먼저(스코프 가드보다도 먼저) 403 `api_key_not_allowed` |
| A5 | `DELETE /users/:id` | `auth-users:write` | 204 |
| A6 | `GET /users/:id/groups` | `auth-users:read` | 200 직접 그룹 id 배열 |
| — | `DELETE /users/:id/lockout` | `auth-users:write` | 204 |
| G1 | `GET /groups` | `auth-groups:read` | 200 그룹 id 배열 |
| G2 | `POST /groups` `{"id"}` | `auth-groups:write` | 201 + `Location`. 이미 있는 id는 409 `CONFLICT`, 틀린 id는 400 |
| G3 | `GET /groups/:id` | `auth-groups:read` | 200 `{"id","members","parents","children"}` |
| G4 | `DELETE /groups/:id` | `auth-groups:write` | 204 |
| G5 / G6 | `PUT` / `DELETE /groups/:id/members/:userId` | `auth-groups:write` | 204, 반복해도 같음 |
| G7 / G8 | `PUT` / `DELETE /groups/:id/parents/:parentId` | `auth-groups:write` | 204(G8은 반복해도 같음). 순환이나 너무 깊은 사슬은 409 `group_cycle` |
| 키 | `GET /apikeys?ownerUserId=` | `auth-apikeys:read` | 200 키 배열(해시 없음, 각 키의 `status`, 비활성 소유자 포함). `ownerUserId`가 없으면 400 `BAD_REQUEST`, 없는 소유자는 404 |
| 키 | `POST /apikeys` `{"ownerUserId","name","scopes","expiresInDays"?,"password"}` | `auth-apikeys:write` | 201 키 + 평문 `key`(한 번만). 아래 [관리 키 발급](#api-키-발급-엔드포인트-pat와-관리-발급)의 판정 순서 |
| 키 | `DELETE /apikeys/:id` | `auth-apikeys:write` | 204. 키 소유자에게 권한 부여 규칙(대상 보호, 403 `insufficient_scope`), 없는 id는 404 |

없는 사용자·그룹 id는 404 `NOT_FOUND`입니다. 비밀번호를 해시하는 A2·A4와 호출자 비밀번호를 확인하는 `POST /apikeys`는 `methodAsync`로 등록하므로 해시 슬롯을 기다리는 동안 worker 스레드를 잡지 않습니다. A2의 `attributes`에 보안 필드 이름(`passwordHash`, `ver`, `enabled`, `roles`)이 있으면 만들기 전에 400 `BAD_REQUEST`입니다.

A4는 바뀌는 모든 필드에 대해 권한 부여 규칙을 먼저 한 번 확인한 뒤 이름(rename 경로: 정규화, 같은 이름이면 아무것도 안 함) → 비밀번호(다시 읽은 `ver`로 `changePassword`, 재시도 없음) → `enabled`(false면 `setEnabled(false)` 뒤 `bumpTokenVersion`) → `roles` → `attributes`(병합, 값이 `null`이면 그 키를 지움) 순서로 적용합니다. 적용한 것만 담은 `onUserUpdated`(origin `ADMIN`) 하나를 내고, 비밀번호를 바꿨으면 `onPasswordChanged`도 냅니다. 단계가 실패하면 A4가 직접 auth 오류 본문에 최상위 `applied` 배열(이미 적용한 A4 요청 필드 이름, 적용 순서)을 붙여 답합니다. 이름 중복은 409 `duplicate_user`, 비밀번호 경쟁에서 지면 409 `write_conflict`, `AuthUnavailableException`은 503과 그 코드(확인되지 않은 보안 쓰기는 `security_write_conflict`), 그 밖의 store 예외는 503 `SERVICE_UNAVAILABLE`입니다. 비밀번호를 적용한 뒤의 단계에서 난 `AuthRejection`·`IllegalArgumentException`도 503 `SERVICE_UNAVAILABLE`입니다. `security_write_conflict`이면 그 감사 이벤트를, 비밀번호 뒤의 다른 실패면 `user_update_failed` 감사 이벤트를 남깁니다. 예: `{"error":{"code":"write_conflict","message":"Conflict","requestId":"…"},"applied":["username"]}`. `.responses(AuthResponses)`를 쓰면 그 본문이 쓰이고 `applied`는 실리지 않습니다.

G4(그룹 삭제)와 G8(간선 제거): 행위자가 그 그룹과 조상이 주는 스코프를 모두 덮으면 멤버를 세지 않고 진행합니다(키 행위자는 `*`를 덮지 못합니다). 아니면 직접·간접 멤버를 최대 1,001명까지 세어, 1,000명을 넘으면 409 `group_too_large`이고, 아니면 행위자가 각 사용자의 지금 스코프를 덮어야 합니다. 행위자 자신이 영향을 받으면(자기 그룹을 통해) 자기 규칙으로 거부합니다. G5와 G7은 그 그룹과 조상이 주는 스코프를 행위자가 덮어야 합니다(G7은 자식 그룹에 멤버가 있을 때).

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
        .lockout(Lockout.after(5).forMinutes(15))
        .userCacheTtl(5_000)
        .build();
auth.install(app);
AuthUser alice = auth.users().create("alice", "alice-password-1".toCharArray());
TokenPair pair = auth.tokens().issue(alice);
app.ws("/ws", auth.webSocket((rq, ws) -> ws.sendText("hi " + auth.user(rq).getId())));
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .lockout(Lockout.after(5).forMinutes(15))
    .userCacheTtl(5_000)
    .build()
val alice = auth.users().create("alice", "alice-password-1".toCharArray())
val pair: TokenPair = auth.tokens().issue(alice)
app.ws("/ws", auth.webSocket { req, ws -> ws.sendText("hi " + auth.user(req).id) })
```

## refresh 재사용 감지와 TokenCodec

**refresh 재사용 감지:** 이미 쓴 refresh 토큰이 재사용 유예 창(`.reuseGrace(millis)`, 기본 0) 밖에서 다시 오면 `TokenService`가 `RefreshTokenStore.revokeFamily`로 그 계열 전체를 폐기하고 `AuthEvents.onRefreshTokenReused(userId, familyId, ctx)`를 한 번 부른 뒤, 다른 무효 refresh 토큰과 똑같은 401 `invalid_refresh_token`으로 답합니다(감지했다는 사실이 응답에 드러나지 않습니다). 유예 0이면 1ms 뒤의 두 번째 사용도 재사용입니다. 유예 창 안의 반복(예: 탭 둘이 동시에 refresh)은 401로만 거부하고 아무것도 폐기하지 않습니다. 같은 사용자의 다른 계열은 영향을 받지 않습니다. 로그아웃이 토큰의 신원을 볼 때는 토큰을 소비하지 않으므로 사용된·폐기된 토큰은 신원을 주지 않고 재사용 감지도 일으키지 않습니다. 재사용 처리 중의 store 오류는 성공도 무효 토큰도 아닌 `AuthUnavailableException`(503)입니다. Express 대응: refresh 토큰 회전 라이브러리의 "reuse detection" 옵션.

**토큰 형식: `TokenCodec`.** `spi.TokenCodec`는 `String encode(JwtClaims claims)`와 `JwtClaims decode(String token, String expectedType)`(둘 다 `throws Exception`, null 없음)를 가진 토큰 형식 교체 지점입니다. auth는 access·refresh 토큰을 모두 설정된 codec으로만 만들고 읽으며, 빌더 `.tokenCodec(codec)`으로 바꿉니다. 기본은 키 소스 위의 HS256 codec(헤더 `kid`, `typ` `at+jwt`/`rt+jwt`, 목적별 파생 키)이고, `expectedType`(`TokenCodec.ACCESS` 또는 `TokenCodec.REFRESH`)이 다른 토큰은 거부합니다. `decode`가 던지면 서명이 틀린 토큰과 같은 401 `invalid_token`이고 이유는 DEBUG 로그에만 남습니다. 0.11.0에는 다른 codec이 없습니다(RS256·ES256·JWKS는 이후 버전). 0.11.0부터는 default 메서드만 더합니다. Express 대응: `express-jwt`에 넘기는 `sign`/`verify` 쌍.

```java
TokenCodec wrapped = new TokenCodec() {
    @Override
    public String encode(JwtClaims claims) throws Exception {
        return Jwt.builder(JwtKeys.of(new byte[32])).build().sign(claims); // 예: 다른 키나 형식
    }

    @Override
    public JwtClaims decode(String token, String expectedType) throws Exception {
        return Jwt.builder(JwtKeys.of(new byte[32])).build().verify(token, expectedType);
    }
};
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).tokenCodec(wrapped).reuseGrace(0).build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .reuseGrace(0)
    .events(object : AuthEvents {
        override fun onRefreshTokenReused(userId: String, familyId: String, ctx: AuthEventContext) {
            println("refresh reuse: $userId / $familyId") // 경보로 보냄
        }
    })
    .build()
```

## CSRF: 쿠키 모드의 쓰기

`COOKIE`·`BOTH` 모드에서 `naru_at` 쿠키로 인증한 POST·PUT·PATCH·DELETE(보호 경로와 `POST /auth/password`, `POST /auth/me/apikeys`, `POST /auth/admin/apikeys`)는 `X-CSRF-Token` 헤더가 `naru_csrf` 쿠키 값과 상수 시간으로 같고 그 값의 HMAC이 제시한 access 토큰의 `sub`·`fam`과 맞을 때만 통과합니다. 아니면 403 `csrf_failed`입니다. 공개 경로(`.permitPrefix`, `.protectOnly` 범위 밖)에서는 CSRF 토큰이나 Origin이 맞지 않는 쿠키 쓰기를 익명 요청으로 다루므로, 그 경로의 가드(`auth.authenticated()`, `auth.require(...)` 등)는 401 `unauthenticated`로 답하고 가드 없는 핸들러에서는 `auth.userOrNull(req)`가 `null`입니다. 토큰은 `base64url(random16) + "." + base64url(HMAC(csrf 키, sub + "." + fam + "." + random16))`이고 키는 키 세트의 비밀에서 `"naru-csrf"`로 파생합니다(회전 중인 옛 kid로 만든 값도 통과). 계열에 묶이므로 다른 경로·도메인에서 심은 옛 계열 값은 통과하지 못하고, `naru_csrf`가 둘 이상이면 헤더와 같으면서 이 요청의 계열과 맞는 값이 하나라도 있으면 통과합니다. GET·HEAD·OPTIONS, Bearer 요청, API 키 요청은 검사하지 않습니다. 쿠키로 토큰을 주는 응답은 언제나 새 쌍의 `sub`·`fam`으로 새 `naru_csrf`를 심고 본문에는 `csrfToken`만 둡니다. 브라우저 앱은 그 값을(또는 `naru_csrf` 쿠키를 읽어) 쓰기 요청의 `X-CSRF-Token`에 넣습니다. Express 대응: `csrf-csrf`의 signed double-submit 쿠키.

```java
// 브라우저 쪽(개념): fetch("/api/posts", {method: "POST", credentials: "include",
//   headers: {"Content-Type": "application/json", "X-CSRF-Token": csrfToken}, body: "{}"})
Auth guard = Auth.builder(System.getenv("JWT_SECRET")).transport(TokenTransport.COOKIE).build();
guard.install(app);
app.post("/api/posts", guard.require("posts:write"), (rq, rs) -> rs.status(201).send("created"));
```

```kotlin
// `auth`는 위에서 만든 Auth입니다
app.post("/api/posts", auth.require("posts:write")) { _, rs -> rs.status(201).send("created") }
```

## 신뢰 기기: DeviceTrust

비밀번호 로그인에 성공하면 auth는 기기 토큰을 발급합니다. HS256 토큰(헤더 `kid`, `typ` `dt+jwt`)이며 사용자 id(`sub`), 발급 때의 `ver`, 정규화한 로그인 이름의 지문 `fp` = base64url(HMAC(지문 키, 이름)), 무작위 `jti`, 90일 뒤의 `exp`를 담고 로그인 이름 자체는 담지 않습니다. 서명 키는 HMAC(비밀, `"naru-device"`), 지문 키는 HMAC(비밀, `"naru-device-fp"`)로 토큰 헤더의 kid에서 파생합니다. 다음 로그인에서 서명·만료(유예 없음)·지문·사용자 id·`ver`가 모두 맞고 실패 한도로 무효가 되지 않았을 때만 신뢰합니다. 그래서 모든 기기 로그아웃이나 비밀번호 변경(`ver++`), 이름 변경, 다른 사용자가 같은 이름을 가져간 경우, 같은 사용자를 다른 이름(예: 이메일)으로 찾은 경우에는 신뢰하지 않습니다. 키를 회전해도 옛 kid가 검증 목록에 있는 동안은 신뢰가 이어집니다. 신뢰한 기기 토큰을 들고 실패한 로그인은 그 토큰에 대해 15분 고정 창에 5회까지 세고, 5번째 실패에서 토큰을 무효로 합니다(훔친 토큰으로 IP를 바꿔 가며 시도하는 것을 막습니다).

`spi.DeviceTrust`는 그 상태 저장소입니다. auth가 서명·검증·지문을 직접 하고(키는 이 인터페이스로 가지 않습니다) 로그인의 세 지점에서 부릅니다: 성공한 로그인이 토큰을 발급한 뒤 `default void issued(DeviceToken token)`, 검사 순서 ③에서 신뢰를 정할 때 `boolean isInvalidated(DeviceToken token)`, 신뢰한 토큰을 든 실패마다 `boolean recordFailure(DeviceToken token, int max, long windowMillis)`(auth는 5회/15분을 넘기고, 이 호출 뒤 무효면 true), 그리고 10분 정리 작업이 부르는 `default int purgeExpired(long nowMillis)`(만료한 토큰의 상태를 지움, 기본은 아무것도 지우지 않음). 모두 동기·`throws Exception`이며 실패는 503입니다. `spi.DeviceToken`은 검증된 토큰의 값 객체(`getTokenId()`, `getUserId()`, `getTokenVersion()`, `getExpiresAt()`)입니다. 빌더 `.deviceTrust(trust)`로 바꾸고, 부르지 않으면 사용자 저장소를 따릅니다(메모리, `FileUserStore`면 그 옆의 `<이름>-device-trust.json`. 무효 표시는 곧바로 파일에 씁니다). 0.11.0부터는 default 메서드만 더합니다. Express 대응: OWASP "device cookie" 패턴의 기기 쿠키 표.

`naru_device` 쿠키(쿠키 전달일 때): HttpOnly, SameSite=Strict, Path = 마운트 경로 + `/login`(기본 `/auth/login`, `.mountPath("/api/auth")`면 `/api/auth/login`), `req.isSecure()`일 때만 Secure, Max-Age = 기기 토큰 수명 90일(7776000초). HEADER 모드는 본문 `deviceToken`으로 주고 `Naru-Device` 요청 헤더만 읽습니다. 로그인 엔드포인트(`POST /auth/login`)는 이 순서로 동작합니다: ① 서명·만료·지문이 맞는 기기 토큰이면 IP 키 검사를 미루고, 아니면 IP 키를 먼저 봅니다 ② `(사용자, IP)` 키 ③ `findByUsername` 후 id·`ver`·`isInvalidated`로 신뢰를 정하고, 신뢰하면 사용자 키와 IP 키를 면제, 아니면 미룬 IP 키와 사용자 키를 봅니다 ④ 해시. ①–③에서 한도에 걸리면 해시 없이 즉시 429입니다. 해시 단계에 닿은 시도는 계정 종류와 상관없이 해시 슬롯 하나에서 PBKDF2를 정확히 한 번 돌리고(레거시 해시는 레거시 검증 + 목표 비용의 더미 PBKDF2, 형식이 깨진 PBKDF2 저장 해시는 목표 비용의 더미 PBKDF2), 실패 응답(슬롯 큐가 찬 503 포함)은 요청 시작 뒤 max(1.25T, 경과) + 0–50ms 무작위 지연 전에는 나가지 않습니다(T는 기동 때 정한 로그인 시간 기준). 기다리는 동안 작업 스레드를 붙잡지 않습니다.

```java
DeviceTrust audited = new DeviceTrust() {
    final java.util.Set<String> dead = new java.util.HashSet<>();

    @Override
    public synchronized boolean isInvalidated(DeviceToken token) {
        return dead.contains(token.getTokenId());
    }

    @Override
    public synchronized boolean recordFailure(DeviceToken token, int max, long windowMillis) {
        return false; // 예: 실패를 외부 저장소에 세고 max에 닿으면 dead에 넣음
    }
};
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).deviceTrust(audited).build();
```

```kotlin
class LoggingTrust : DeviceTrust {
    override fun isInvalidated(token: DeviceToken) = false
    override fun recordFailure(token: DeviceToken, max: Int, windowMillis: Long): Boolean {
        println("device ${token.tokenId} failed")
        return false
    }
}
val auth = Auth.builder(System.getenv("JWT_SECRET")).deviceTrust(LoggingTrust()).build()
```

## 교차 출처 클라이언트와 CORS

다른 출처의 웹 앱이나 Capacitor 같은 앱이 auth를 부르면 브라우저가 먼저 preflight를 보냅니다. 클라이언트가 보내는 헤더는 모두 `app.cors(...)`의 `allowedHeaders`에 있어야 합니다.

| 헤더 | 필요한 경우 |
|---|---|
| `Authorization` | Bearer access 토큰을 보낼 때, 그리고 API 키를 키 스킴(기본 `ApiKey`, `.apiKeyScheme(..)`)으로 보낼 때 |
| `Content-Type` | JSON 본문(`application/json`)을 보낼 때(로그인, refresh, 모든 auth 본문) |
| `X-CSRF-Token` | 쿠키 전달을 받은 클라이언트가 쿠키로 인증한 쓰기(POST·PUT·PATCH·DELETE)와 쿠키 refresh를 보낼 때 |
| `Naru-Device` | 기기 토큰을 헤더로 보낼 때(HEADER 모드, 또는 BOTH에서 본문으로 받은 기기 토큰) |

`allowedHeaders`에 없는 헤더(예: `Naru-Device`)를 실은 교차 출처 요청은 preflight에서 막혀 **서버에 닿지 않습니다**. 그래서 auth 로그에도 아무것도 남지 않습니다. `app.cors(...)`는 반드시 `auth.install(app)`보다 먼저 등록합니다(나중에 등록하면 doctor가 기동을 FAIL로 멈춥니다). BOTH 모드에서 본문 전달을 받을 출처(다른 사이트의 SPA, Capacitor 앱)는 `allowedOrigins`에만 넣고 `trustedOrigins`(빌더 `.trustedOrigins(...)`, [Origin 검사](#origin-검사와-trustedorigins))에는 넣지 마세요. `trustedOrigins`에 넣으면 그 출처는 쿠키 전달로 바뀌는데, SameSite=Strict 쿠키는 교차 사이트 요청에 실리지 않으므로 그 클라이언트는 쿠키를 다시 보내지 못합니다. `allowedOrigins`는 응답을 읽게 해 줄 뿐이고, 쿠키 전달 여부는 `trustedOrigins`가 정합니다.

BOTH 모드 클라이언트는 토큰을 주는 응답마다 본문을 확인합니다. `accessToken`이 있으면 본문 전달입니다(토큰을 보관하고 Bearer로 보냄). `csrfToken`만 있으면 쿠키 전달입니다(`X-CSRF-Token`을 보냄). 같은 클라이언트도 Origin과 `trustedOrigins`에 따라 어느 쪽이든 받을 수 있으니 한 모양을 가정하지 마세요.

`allowedOrigins`는 `scheme://host[:port]` 출처·`null`·`*`를 받습니다. iOS Capacitor는 `capacitor://localhost`(옛 Ionic WebView는 `ionic://localhost`)이고, Android Capacitor는 기본이 `https://localhost`이며 `androidScheme: "http"`를 쓰는 옛 앱만 `http://localhost`입니다. 비교는 문자열 일치이므로 앱이 실제로 보내는 값을 넣으세요. 이 출처는 앱의 신원이 아닙니다. 기본 설정의 Capacitor 앱은 모두 같은 값을 보내므로 다른 Capacitor 앱의 웹뷰도 응답을 읽을 수 있고, 사용자의 토큰 없이 얻는 것은 공개 응답뿐입니다. 커스텀 스킴 출처는 `trustedOrigins`에는 넣을 수 없어 언제나 본문 전달입니다. CapacitorHttp로 보내는 요청은 `Origin`이 없어 같은 본문 전달이고 CORS 설정이 필요 없습니다. Express 대응: `app.use(cors({origin: [...], allowedHeaders: [...], credentials: true}))`를 인증 미들웨어보다 먼저 둡니다.

```java
RestApp web = RestApp.create();
web.cors(c -> c
        .allowedOrigins("https://spa.example", "capacitor://localhost", "https://localhost")
        .allowedMethods(Method.GET, Method.POST, Method.PUT, Method.DELETE)
        .allowedHeaders("Authorization", "Content-Type", "X-CSRF-Token", "Naru-Device")
        .credentials(true));
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).transport(TokenTransport.BOTH).build();
auth.install(web); // CORS 다음
```

```kotlin
val web = RestApp.create()
web.cors { c ->
    c.allowedOrigins("https://spa.example", "capacitor://localhost", "https://localhost")
        .allowedHeaders("Authorization", "Content-Type", "X-CSRF-Token", "Naru-Device")
        .credentials(true)
}
Auth.builder(System.getenv("JWT_SECRET")).transport(TokenTransport.BOTH).build().install(web)
```

## 테스트 킷: AuthTesting

`utils.auth.testing.AuthTesting`은 `NaruServe-api` 주 jar에 들어 있고 테스트 프레임워크가 필요 없습니다. 토큰과 키는 정상 발급 경로(`auth.tokens()`, API 키 저장소)로 만들며 서명 키를 복사하거나 꺼내지 않습니다.

| API | 설명 |
|---|---|
| `AuthTesting.tokenFor(auth, name, roles...)` | `InMemoryUserStore`에 그 이름의 사용자를 만들거나 역할을 덮어쓰고, 지금 `ver`의 access 토큰을 줍니다(refresh 기록 없음, 합성 `fam`). 역할은 `RolePolicy`에 선언돼야 하고(아니면 `IllegalArgumentException`), 다른 저장소면 `IllegalStateException`입니다. `tokenFor(auth, "alice")`는 역할 없는 토큰입니다 |
| `AuthTesting.tokenForUserId(auth, id)` | 어떤 저장소든 그 사용자의 지금 `ver`로 토큰을 주고 사용자를 바꾸지 않습니다. 없는 id면 `IllegalArgumentException("no user with id …")` |
| `AuthTesting.csrfTokenFor(auth, accessToken)` | 그 토큰의 `sub`·`fam`에 묶인 CSRF 값. 쿠키 모드 쓰기 테스트에서 `naru_csrf` 쿠키와 `X-CSRF-Token` 헤더에 넣습니다 |
| `AuthTesting.apiKeyFor(auth, ownerUserId, scopes...)` | 만료 없는 실제 API 키를 `.apiKeys(store)`에 저장하고 평문 키를 줍니다. 정상 발급 경로 `auth.apiKeys().issue`를 거치므로 어떤 `ApiKeyStore`든 되고 `.maxKeysPerUser` 한도(넘으면 `AuthRejection(409, "api_key_limit")`)도 같습니다. 없는 소유자는 `IllegalArgumentException`, 키 저장소가 없으면 `AuthStateException`. `Authorization: <스킴> <키>`로 보냅니다 |
| `AuthTesting.verifyUserStore(store)` | 직접 만든 `UserStore`를 계약에 맞춰 검사합니다. CRUD(지운 id를 다음 사용자에게 다시 주지 않는지 포함, `MAX(id)+1`이나 SQLite rowid 저장소는 실패), 같은 이름의 `DuplicateUserException`, 같은 인스턴스로 저장·재조회한 비밀번호 해시 왕복(행을 메모리에 캐시하고 저장할 때 해시를 잃는 저장소도 통과하므로 아래 `reopen` 형태를 쓰세요), `changePassword`(해시가 null인 사용자, ver 불일치), 필드 메서드 결과, 그리고 `supportsAtomicFieldWrites()`가 true인 저장소는 `update()`가 passwordHash·ver·enabled·roles를 덮어쓰지 않는지 봅니다. 깨진 항목은 `AssertionError`(메시지는 `UserStore contract (<항목>)`)입니다. 호출마다 `contract-`로 시작하는 임의 이름을 쓰고 만든 사용자를 지우므로 같은 저장소를 여러 번 검사해도 됩니다 |
| `AuthTesting.verifyUserStore(store, reopen)` | 위 검사에 재시작 뒤 해시 왕복을 더합니다. `reopen`(`Callable<? extends UserStore>`, Java·Kotlin 람다)은 같은 저장소 위에 새 인스턴스를 열고, `create()`로 쓰고 `update()`를 거친 해시가 그 인스턴스에서 id와 이름으로 그대로 읽혀야 합니다. 파일 저장소는 `() -> new FileUserStore(file)`, 호출마다 저장소를 읽는 저장소(JDBC)는 `() -> store`를 넘깁니다. 예외를 던지거나 null을 돌려주는 `reopen`은 계약 실패이고, `store`가 아닌 새 인스턴스가 `AutoCloseable`이면 닫습니다. Express 대응: jest 계약 스위트 안에서 앱을 다시 시작하기 |
| `AuthTesting.verifyApiKeyStore(store)` / `verifyApiKeyStore(a, b)` | `ApiKeyStore` 메서드 표를 검사합니다(필드 왕복과 null `expiresAt`·`lastUsedAt`·`issuedByUserId`, 없는 prefix는 null, 기존 prefix는 `ApiKeyPrefixCollisionException`, `aB…`와 `Ab…`는 다른 행, 만료·지난 `ownerVer` 키를 빼고 세는 한도, 다른 소유자의 `deleteByIdAndOwner`는 false, `deleteAllByOwner`, `deleteExpired`, `deleteStaleByOwner`·`deleteFutureByOwner`). 인자 둘은 같은 저장소 위의 두 인스턴스로 8 스레드 동시 한도 삽입에서 정확히 하나만 이기는지도 봅니다. 깨진 규칙은 `AssertionError`(메시지는 `ApiKeyStore contract:`로 시작하고 메서드 이름을 담음)입니다 |

Express 대응: supertest 테스트가 로그인 라우트를 불러 토큰을 얻는 도우미, 저장소마다 복사하던 jest 계약 테스트.

PROD가 필요한 테스트는 실제 비밀키와 파일 키를 JVM에서 무작위 바이트로 만들 수 있습니다(`java.util.Base64`는 Android API 26 이상).

```java
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.AuthFileKey;
import com.snoworca.naruserve.utils.auth.AuthMode;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import java.io.File;
import java.security.SecureRandom;
import java.util.Base64;

byte[] raw = new byte[32];
new SecureRandom().nextBytes(raw);
String secret = Base64.getEncoder().encodeToString(raw);   // Auth.builder(secret)에 넘깁니다
new SecureRandom().nextBytes(raw);
AuthFileKey fileKey = AuthFileKey.of(Base64.getEncoder().encodeToString(raw));
Auth prod = Auth.builder(secret).mode(AuthMode.PROD)
        .users(new FileUserStore(new File("build/test-users.json"), fileKey)).build();
```

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
        .roles(RolePolicy.builder().role("editor", "posts:write").build()).build();
String access = com.snoworca.naruserve.utils.auth.testing.AuthTesting.tokenFor(auth, "alice", "editor");
String csrf = com.snoworca.naruserve.utils.auth.testing.AuthTesting.csrfTokenFor(auth, access);
// Cookie: naru_at=<access>; naru_csrf=<csrf>   X-CSRF-Token: <csrf>
com.snoworca.naruserve.utils.auth.testing.AuthTesting.verifyUserStore(new InMemoryUserStore());
```

```kotlin
import org.junit.Test

// `MyUserStore`는 앱의 UserStore 클래스, `ds`는 그것이 여는 대상(DataSource, 파일), `auth`는 위와 같습니다
class MyStoreTest {
    @Test fun myStoreKeepsTheContract() = AuthTesting.verifyUserStore(MyUserStore(ds))
    @Test fun bearerWrite() {
        val token = AuthTesting.tokenFor(auth, "alice", "editor")
        // Authorization: Bearer $token
    }
}
```

### HTTP 테스트 레시피

앱 전체를 실제 HTTP로 시험하는 JUnit 4 테스트입니다. DEV 모드와 `.user(...)`로 계정을 만들고, `.userCacheTtl(0)`으로 캐시 기다림을 없애고, `app.listen(0)`으로 빈 포트에 띄웁니다. `HttpURLConnection`으로 `POST /auth/login`을 불러 토큰을 받고, `Authorization: Bearer <토큰>`으로 보호 경로를 부른 뒤, `@After`에서 `app.shutdown(...)`으로 끕니다. 의존성은 `com.snoworca.naruserve:naruserve`와 JUnit 4이고, 다른 HTTP 클라이언트(OkHttp 등)를 써도 됩니다. 토큰만 필요하고 로그인 경로를 시험하지 않는다면 위의 `AuthTesting.tokenFor`가 더 빠릅니다. `call` 도우미는 `json`이 null이 아닐 때만 본문을 씁니다. 본문이 있으면 `HttpURLConnection`이 GET을 POST로 바꾸기 때문이며, GET은 `null`을 넘깁니다. `HttpURLConnection`은 `PATCH`를 보낼 수 없습니다(`setRequestMethod("PATCH")`가 `ProtocolException`). `PATCH /auth/me`나 관리 A4 수정을 시험하려면 `java.net.http.HttpClient`(Java 11 이상)나 OkHttp를 쓰세요. `java.net.http.HttpClient`는 Java 11 이상이 필요하므로 Java 8이나 Android 대상 테스트는 OkHttp(`com.squareup.okhttp3:okhttp`)를 씁니다. 한 이름이 한 IP에서 15분 안에 로그인에 다섯 번 실패하면 맞는 비밀번호도 429 `too_many_attempts`가 되므로, 틀린 비밀번호를 시험하는 테스트는 테스트마다 새 앱을 만들거나 `.loginFailureLimits(100, 100, 100, 60_000L)`을 설정합니다.

```java
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.AuthMode;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PostsApiTest {
    private RestApp app;
    private int port;

    @Before
    public void start() {
        Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
            .mode(AuthMode.DEV)                                   // 테스트: 비밀키가 없으면 임시 키
            .userCacheTtl(0)                                      // store에 쓴 변경이 다음 요청에 바로 보임
            .roles(RolePolicy.builder().role("editor", "posts:write").build())
            .user("alice", "alice-password-1", "editor")
            .build();
        app = RestApp.create();
        app.use(auth);
        app.post("/posts", auth.require("posts:write"), (req, res) -> res.status(201).send("created"));
        app.onError(auth.errors());
        port = app.listen(0).port();                              // 0이면 빈 포트를 고름
    }

    @After
    public void stop() {
        app.shutdown(5, TimeUnit.SECONDS);
    }

    @Test
    public void editorCanPost() throws Exception {
        String login = call("POST", "/auth/login", null, "{\"username\":\"alice\",\"password\":\"alice-password-1\"}");
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(login);
        assertTrue(login, m.find());
        assertEquals("201 created", call("POST", "/posts", m.group(1), "{}"));
        assertTrue(call("POST", "/posts", null, "{}").startsWith("401"));
    }

    private String call(String method, String path, String token, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (json != null) { // 본문이 있으면 HttpURLConnection이 GET을 POST로 바꿉니다
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = c.getResponseCode();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
            byte[] buf = new byte[1024];
            for (int n = in.read(buf); n > 0; n = in.read(buf)) {
                body.write(buf, 0, n);
            }
        }
        c.disconnect();
        return status + " " + new String(body.toByteArray(), StandardCharsets.UTF_8);
    }
}
```

```kotlin
import com.snoworca.naruserve.RestApp
import com.snoworca.naruserve.utils.auth.Auth
import com.snoworca.naruserve.utils.auth.AuthMode
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PostsApiTest {
    private lateinit var app: RestApp
    private var port = 0

    @Before
    fun start() {
        val auth = Auth.builder(System.getenv("JWT_SECRET"))
            .mode(AuthMode.DEV)
            .userCacheTtl(0)
            .roles(RolePolicy.builder().role("editor", "posts:write").build())
            .user("alice", "alice-password-1", "editor")
            .build()
        app = RestApp.create()
        app.use(auth)
        app.post("/posts", auth.require("posts:write")) { _, res -> res.status(201).send("created") }
        app.onError(auth.errors())
        port = app.listen(0).port()
    }

    @After
    fun stop() {
        app.shutdown(5, TimeUnit.SECONDS)
    }

    @Test
    fun editorCanPost() {
        val login = call("POST", "/auth/login", null, """{"username":"alice","password":"alice-password-1"}""")
        val token = Regex("\"accessToken\":\"([^\"]+)\"").find(login)?.groupValues?.get(1)
        assertTrue(login, token != null)
        assertEquals("201 created", call("POST", "/posts", token, "{}"))
        assertTrue(call("POST", "/posts", null, "{}").startsWith("401"))
    }

    private fun call(method: String, path: String, token: String?, json: String?): String {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (json != null) { // 본문이 있으면 HttpURLConnection이 GET을 POST로 바꿉니다
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }
        val status = c.responseCode
        val body = (if (status >= 400) c.errorStream else c.inputStream).use { it.readBytes().toString(Charsets.UTF_8) }
        c.disconnect()
        return "$status $body"
    }
}
```

## 평문 전송 허용: allowInsecureTransport

`.allowInsecureTransport(reason)`은 PROD의 보안 연결 검사를 끄는 유일한 스위치입니다(DEV는 이것 없이도 끕니다). 대신 토큰 수명을 고정합니다. access 토큰 5분, refresh 토큰 24시간이며 `.accessTtl`·`.refreshTtl`·프로필 값을 덮어씁니다. 기동할 때마다 이유 문자열을 담은 WARN을 한 줄 남기고, 쿠키 전달이 평문으로 나가 auth 쿠키에 Secure가 빠지면 그 사실을 WARN으로 남깁니다. 이유가 비었으면 `build()`가 거부합니다. TLS 어댑터가 생기면 지우세요.

Express 대응: `https` 없이 `http.createServer(app)`로 띄운 사내 기기 서버. Express는 평문을 막지 않으므로 이런 스위치가 따로 없습니다.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .allowInsecureTransport("LAN device server; TLS adapter pending")   // access 5분, refresh 24시간
    .build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .allowInsecureTransport("LAN device server; TLS adapter pending")
    .build()
println(auth.tokens().accessTtl)
```

## 계정 엔드포인트: auth.routes()

`auth.install(app)`이 마운트 경로(기본 `/auth`)에 계정 엔드포인트를 둡니다. 앱이 `auth.routes()`에 같은 경로를 등록하면 앱의 라우트가 먼저 돕니다. 모든 auth POST 본문은 JSON이고(아니면 415), 본문은 앱의 JSON 코덱으로 읽습니다. 해시를 쓰는 login·password·register·setup과(키를 켜면) `POST /auth/me/apikeys`는 `methodAsync`로 등록하므로 해시 슬롯을 기다리는 동안 worker 스레드를 잡지 않습니다. 등록되는 계정 엔드포인트는 아래 표의 여덟이고(`POST /auth/register`는 `.register(true)`일 때만, `POST /auth/setup`은 setup 상태와 상관없이 등록하고 닫혀 있으면 404), `.apiKeys(store)`가 있으면 `GET`·`POST /auth/me/apikeys`와 `DELETE /auth/me/apikeys/:id` 셋이 더해집니다. `/signup`, `/password/forgot`, `/password/reset`, `/verify-email/request`, `/verify-email`은 없습니다(v2). 토큰을 주는 응답은 모두 `Cache-Control: no-store`이고 [전달 규칙](#교차-출처-클라이언트와-cors)을 따릅니다: HEADER는 본문(`accessToken`, `tokenType`, `expiresIn`, `refreshToken`, `refreshExpiresIn`, 로그인이면 `deviceToken`), COOKIE는 쿠키(`naru_at`, `naru_rt`, `naru_csrf`, 로그인이면 `naru_device`)와 본문 `csrfToken`만, BOTH는 Origin(없으면 Referer)이 요청 자신의 출처나 `trustedOrigins`에 있으면 쿠키, 아니면 본문입니다.

| 엔드포인트 | 본문 | 성공 | 실패 | Express 대응 |
|---|---|---|---|---|
| `POST /auth/login` | `{"username","password"}` | 200, 새 계열의 토큰 쌍 + 기기 토큰 | 없는 이름·틀린 비밀번호·비활성·서비스 계정·잠긴 계정은 모두 같은 401 `invalid_credentials`(해시 한 번, 실패 응답 하한 뒤). `password`가 없거나 문자열이 아니거나 `""`이면 해시 없이 400 `BAD_REQUEST`. 제한에 걸리면 해시 없이 429 `too_many_attempts` + `Retry-After` | passport-local 로그인 라우트 |
| `POST /auth/refresh` | `{"refreshToken"}`(HEADER·BOTH), 없으면 `naru_rt` 쿠키(COOKIE·BOTH) | 200, 같은 계열의 새 쌍. 본문 토큰은 본문으로, 쿠키 토큰은 쿠키로 답함 | refresh 토큰이 없거나 무효·폐기·만료·재사용이면 401 `invalid_refresh_token`. 쿠키 토큰에 `X-CSRF-Token`이 없거나 Origin이 다르면 403 `csrf_failed`. COOKIE의 본문 `refreshToken`은 400 `BAD_REQUEST` | JWT 설정의 refresh 라우트 |
| `POST /auth/password` | `{"currentPassword","newPassword"}` | 200, **새** 계열의 새 쌍 + 새 기기 토큰(쿠키면 새 CSRF 토큰도) | 지금 비밀번호가 틀리면 401 `invalid_credentials`(사용자별 제한에 셈, 넘으면 429 `too_many_attempts`). 세션이 끊겼으면(다른 `ver`, 비활성) 401 `invalid_token`. 다른 쓰기가 먼저 비밀번호를 바꿨으면 409 `write_conflict`. 정책 위반 422, 필드가 없으면 400 `BAD_REQUEST` | 비밀번호 변경 라우트 |
| `POST /auth/logout[?all=true]` | 선택 `{"refreshToken"}`(HEADER·BOTH) | 204. COOKIE·BOTH는 auth 쿠키를 `Max-Age=0`으로 지움. 그냥 로그아웃하면 refresh 토큰 계열만 끊기고 이미 받은 access 토큰은 만료될 때까지 유효합니다(`expiresIn`, 기본 프로필에서 15분). 지금 끊으려면 `?all=true`를 보냅니다. 사용자의 `ver`를 올려 모든 기기의 access 토큰을 끝냅니다 | `?all=true`인데 신원이 없으면 토큰을 읽었을 때 401 `invalid_token`, 읽은 토큰이 없으면 401 `unauthenticated`. COOKIE의 본문 `refreshToken`은 400 | `req.logout()` |
| `POST /auth/register` (`.register(true)`일 때만) | `{"username","password"}` | 201, 토큰 쌍 | 이름이 있으면 409 `duplicate_user`. 비밀번호 정책 위반 422 | 가입 라우트 |
| `GET /auth/me` | — | 200 `{"id","username","enabled","roles","attributes","serviceAccount"}`(해시와 토큰 버전 없음. `roles`는 사용자 자신의 역할뿐이라 그룹에서 받은 역할은 없고, 그것까지 든 것은 `auth.user(req).getRoles()`입니다). API 키로도 됩니다 | 자격 증명이 없으면 401 `unauthenticated` | `req.user`를 돌려주는 `/me` 라우트 |
| `PATCH /auth/me` | `{"attributes":{"displayName":"Al"}}` | 200, 바뀐 프로필 | `.allowedAttributes`에 없는 속성은 400 `BAD_REQUEST`(아무것도 바뀌지 않음). `beforeUserUpdate`의 거절은 그 상태와 코드(예: 422 `name_policy`). API 키는 403 `api_key_not_allowed`. 자격 증명이 없으면 401 `unauthenticated` | 프로필 수정 라우트 |
| `POST /auth/setup` | `{"token","username","password"}` | 201(만듦) / 200(복구) + 토큰 쌍 | 닫혀 있으면 404. 로컬이 아니면 403 `setup_not_local`. `token`이 없거나 문자열이 아니면 400 `BAD_REQUEST`, 틀리면 401 `invalid_setup_token` | — |

**로그인**: 이름은 NFKC + `Locale.ROOT` 소문자로 정규화하므로 `ALICE`로 `alice`가 들어옵니다. 검사 순서는 [신뢰 기기](#신뢰-기기-devicetrust)의 ①–④이고, 비밀번호가 맞고 사용자가 활성일 때만 `beforeLogin`이 해시 없는 `AuthUser`를 받습니다. 리스너의 `AuthRejection`(예: 403 `blocked`)은 실패 응답 하한 뒤에 나가고, 틀린 비밀번호·없는 이름 등에는 리스너가 불리지 않으므로 계정의 존재가 드러나지 않습니다. 성공하면 `onLogin`. 기기 토큰은 HEADER는 `Naru-Device` 요청 헤더로만, COOKIE는 `naru_device` 쿠키로만, BOTH는 헤더를 먼저 읽습니다. 로그인에 온 access 토큰(만료됐든 유효하든)은 무시하고 주체를 붙이지 않습니다.

**로그아웃**의 신원은 이 표로 정합니다. 앞의 후보를 **보냈으면** 그것만 보고, 보냈는데 무효면 신원은 없습니다(뒤 후보를 보지 않음). 유효하다는 것은 서명·만료·사용자의 지금 `ver`가 맞고, refresh 토큰이면 저장된 기록이 쓰이지도 폐기되지도 않은 것입니다. 로그아웃은 토큰을 사용됨으로 표시하지 않으므로 재사용 감지를 일으키지 않습니다.

| 순서 | 후보 | CSRF·Origin |
|---|---|---|
| ① | 본문 `refreshToken`(HEADER·BOTH) | 검사 안 함. BOTH에서 같은 사용자의 `naru_rt` 계열도 함께 폐기 |
| ② | 하나뿐인 `naru_rt`(COOKIE·BOTH, 둘 이상이면 없는 것) | `X-CSRF-Token`이 그 토큰의 `sub`·`fam`에 맞는 `naru_csrf`와 같고 Origin이 같은 출처나 `trustedOrigins` |
| ③ | 유효한 Bearer(HEADER·BOTH) / 하나뿐인 `naru_at`(COOKIE·BOTH) | Bearer는 검사 안 함 / `naru_at`은 ②와 같은 검사 |
| ④ | 없음 | — |

신원이 있으면 그 토큰의 계열을 `revokeFamily`로 끊고(`?all=true`면 대신 사용자의 `ver`를 정확히 1 올려 모든 기기를 끊음) `onLogout(userId, allDevices, ctx)`를 부릅니다. BOTH에서 Bearer와 `naru_at`을 함께 보내면 표 앞에서 400 `invalid_request`입니다. 신원을 찾는 중 store 오류는 503입니다. `AuthTesting.tokenFor`의 토큰은 합성 계열이라 로그아웃해도 끊을 계열이 없습니다.

**판정 순서**: 모든 auth 엔드포인트는 요청을 한 순서로 판정하고 가장 먼저 걸린 단계가 답합니다: 보안 전송 검사(403 `insecure_transport`), 다중 자격 증명과 API 키 절차(400 `invalid_request`, 401 `invalid_api_key`, 403 `api_key_not_allowed`), 본문 형식(415), COOKIE 모드의 본문 `refreshToken`(400), CSRF·Origin(403 `csrf_failed`, BOTH의 login·register·setup은 403 대신 전달 방식을 고름), 그다음 핸들러. 쿠키 refresh 토큰은 CSRF 검사보다 먼저 검증하므로 폐기되거나 만료된 `naru_rt`는 `X-CSRF-Token`이 있든 없든 401입니다. 닫힌 setup은 없는 라우트로 보아 Origin 단계 전에 404입니다.

**refresh**는 JSON 본문 `refreshToken`을 먼저 받고(HEADER·BOTH, CSRF 없음, 본문 전달), 없으면 `naru_rt` 쿠키 하나를 받습니다(COOKIE·BOTH, CSRF·Origin 검사, 쿠키 전달). HEADER는 쿠키를 읽지 않고 COOKIE는 본문 토큰을 400으로 거절합니다. BOTH에서 둘 다 오면 본문 토큰을 쓰고 `naru_rt`는 검증도 회전도 하지 않습니다. 회전은 계열을 유지하고, 이미 회전된 토큰을 `.reuseGrace(..)` 창 밖에서 다시 내면 계열 전체를 폐기합니다(`onRefreshTokenReused`). refresh와 함께 온 만료된 Bearer나 `naru_at`은 무시합니다. 키를 켜면 키 헤더를 먼저 판정하고(틀린 키 401 `invalid_api_key`, 유효한 키 403 `api_key_not_allowed`) `naru_rt`는 읽지 않습니다. 성공하면 `onTokenRefreshed`.

**비밀번호 변경**은 보호 경로처럼 판정하는 주체 필요 엔드포인트입니다(만료된 토큰은 401 `invalid_token`, 쿠키 세션은 `X-CSRF-Token`과 Origin 통과가 필요, API 키는 403 `api_key_not_allowed`). 정책 검사(422)와 사용자별 제한(지금 비밀번호 5번 틀림/15분, `auth.users().checkPassword`와 공유)은 해시 전에 돕니다. 해시 슬롯 하나에서 사용자를 다시 읽고(요청 토큰과 `ver`가 다르거나 비활성이면 401 `invalid_token`), 지금 비밀번호를 검증하고 새 비밀번호를 해시합니다. 쓰기 전에 `beforeUserUpdate`(origin `SELF`, 해시를 보지 않으며 거절할 수 있음, 예: 403 `password_change_locked`)가 돕니다. 쓰기는 `changePassword(id, oldHash, tokenVer, newHash)` 한 번, 즉 `ver + 1`을 함께 하는 원자적 해시 교체입니다. 다른 쓰기가 이겼으면 다시 읽은 해시가 지금 비밀번호를 여전히 검증할 때(재해시)만 한 번 재시도하고, 아니면 409입니다. 그다음 `onUserUpdated`와 `onPasswordChanged`이고, 새 쌍은 자격 증명이 온 방식대로 나갑니다: Bearer면 본문, `naru_at`이면 쿠키.

**내 프로필**: `GET /auth/me`는 저장소에서 다시 읽은 호출자의 공개 프로필입니다. `PATCH /auth/me`는 `attributes` 객체만 봅니다. 값이 문자열이면 그 속성을 바꾸고 `null`이면 지우며, `.allowedAttributes(...)`에 없는 이름이 하나라도 있으면 400입니다. `attributes` 밖의 `displayName`, `username`·`roles`·`enabled`·`password` 같은 다른 필드가 있으면 아무것도 바꾸지 않고 400 `unknown field <name>; PATCH /auth/me reads only {"attributes":{...}}`입니다. 비밀번호는 `POST /auth/password`로, 이름은 `auth.users().rename`이나 관리자 A4 `PATCH /auth/admin/users/:id`로만 바꿉니다. 쓰기는 `auth.users().update`와 같은 사용자별 잠금 경로이고, `beforeUserUpdate`와 `onUserUpdated`(`ATTRIBUTES`)를 출처 `SELF`로 한 번씩 냅니다. 비밀번호 변경(`POST /auth/password`)도 같은 출처 `SELF`의 `beforeUserUpdate`, `onUserUpdated`(`PASSWORD`), `onPasswordChanged`를 냅니다.

**가입**은 `beforeUserCreate`(출처 `REGISTER`, 거부하면 그 상태와 코드, 예: 422 `domain_not_allowed`)를 해시 전에 부르고, 해시 슬롯에서 해시한 뒤 만들고 `onUserCreated`를 부릅니다. COOKIE 모드는 Origin만 봅니다(교차 출처면 403 `csrf_failed`). 역할 없는 사용자로 만듭니다.

**setup과 복구**: setup은 둘 중 하나로만 열립니다. ① PROD에서 비밀번호 없는 관리자 `.user`의 계정이 없고 복구가 켜졌을 때(그 `.user`의 이름이어야 하고, 다르면 400 `username_mismatch`, 그 `.user`의 역할을 줌) ② `.recovery(true)` 또는 `.recoveryFromEnv()`와 `NARU_AUTH_RECOVERY=1`(기동마다 새 일회용 토큰을 WARN 한 줄에 찍음). 복구는 없는 이름이면 내장 `admin`으로 만들고, 있는 이름이면(저장된 해시가 null이어도) 활성화하고 비밀번호를 바꾸고 `admin`을 더하고 `ver`를 정확히 한 번 올립니다(옛 토큰 무효, 서비스 계정이면 400 `service_account_no_password`). 바뀐 필드를 담은 `onUserUpdated`와 `onPasswordChanged`만 출처 `RECOVERY`로 부릅니다. 로컬 요청(루프백 소켓 주소이고 `X-Forwarded-*`·`Forwarded` 헤더 없음)만 받고, 보안 연결 검사([보안 연결 규칙](#보안-연결-규칙))와 따로 봅니다: 보안 연결이지만 로컬이 아닌 요청은 403 `setup_not_local`이며 토큰은 그대로 남습니다. before 이벤트는 부르지 않고 `onUserCreated`/`onUserUpdated`(출처 `SETUP`·`RECOVERY`)만 부르며 복구마다 `recovery_used` 감사 이벤트를 남깁니다. 한 번 성공하면 그 프로세스에서는 닫혀 404입니다. COOKIE 모드는 Origin을 봅니다.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).register(true).recoveryFromEnv()
        .events(new AuthEvents() {
            @Override
            public void beforeUserCreate(AuthUser candidate, AuthEventContext ctx) {
                if (ctx.getOrigin() == UserOrigin.REGISTER && !candidate.getUsername().endsWith("@corp.example")) {
                    throw new AuthRejection(422, "domain_not_allowed", "use your company address");
                }
            }
        }).build();
auth.install(app);
// POST /auth/login {"username":"alice","password":"..."} -> 200 {"accessToken":...,"refreshToken":...}
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).register(true).build()
auth.install(app)
// fetch("/auth/logout?all=true", {method: "POST", headers: {Authorization: "Bearer " + token}}) -> 204
```

## Origin 검사와 trustedOrigins

`COOKIE`·`BOTH` 모드에서 auth 쿠키를 심는 응답과 쿠키로 온 토큰을 쓰는 요청(쿠키로 인증한 보호 경로 쓰기, 로그아웃의 ②·③)은 요청의 `Origin`(없으면 `Referer`의 출처)이 요청 자신의 출처(`req.isSecure()`와 `Host`)이거나 `.trustedOrigins(...)`에 있어야 합니다. `Origin: null`과 둘 다 없는 요청은 통과하지 못합니다. 로그인·가입·setup은 Origin만 보고 CSRF 토큰은 보지 않습니다. 실패는 COOKIE 모드에서 403 `csrf_failed`이고, BOTH 모드의 로그인·가입·setup은 403 대신 본문 전달을 고릅니다. 출처는 `scheme://host[:port]`(소문자, 기본 포트 생략)로 비교하며, `.trustedOrigins`에 경로가 붙은 값은 `build()`가 거부합니다. Express 대응: `csurf`와 함께 쓰던 Origin 검사 미들웨어.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).transport(TokenTransport.COOKIE)
        .trustedOrigins("https://admin.example.com").build();
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).transport(TokenTransport.BOTH)
    .trustedOrigins("https://spa.example").build()
```

## API 키 인증

`.apiKeys(store)`로 켭니다. 켜면 `/auth/me/apikeys`(`GET` 내 키 목록, 각 키의 `status`는 `active`·`expired`·`stale` 문자열, `POST` 비밀번호를 다시 확인하는 PAT 발급, `DELETE /:id` 내 키 삭제)와 `/auth/admin/apikeys` 세 라우트가 등록됩니다([API 키 발급 엔드포인트](#api-키-발급-엔드포인트-pat와-관리-발급)). 끄면(기본) 키 라우트가 없고 `Authorization: ApiKey ...`는 자격 증명이 아닙니다(401 `unauthenticated`).

키는 `Authorization: <스킴> nk_<prefix8>_<secret32>`로만 받습니다. 스킴은 `.apiKeyScheme(..)`(기본 `ApiKey`, 대소문자 무시)이고 값은 RFC 9110 `token68` 하나여야 합니다(`ApiKey a=b`는 401 `invalid_api_key`). 키와 JWT는 값 모양이 아니라 **스킴**으로 가르므로 `Bearer nk_...`는 잘못된 JWT(401 `invalid_token`)입니다. 쿼리 문자열이나 쿠키의 키는 읽지 않습니다. COOKIE·BOTH에서 키와 `naru_at`을 함께 보내면 보호 경로는 400 `invalid_request`, 공개 경로는 익명입니다(`naru_rt`는 자격 증명으로 세지 않음). `Authorization` 헤더가 둘이면 보호 경로와 자격 증명 엔드포인트는 400, 다른 공개 경로는 읽지 않고 익명입니다. 프록시가 둘을 한 줄로 합친 값은 첫 스킴으로 판정하는 잘못된 자격 증명 하나입니다.

검증은 이 순서이고, 조건이 겹치면 가장 앞 단계가 답합니다: 보안 전송 검사, 다중 자격 증명(400), 스킴, 키 전용 IP 제한(429), 파싱, prefix 조회(없으면 더미 해시와 비교, 대소문자를 무시하는 DB 정렬이 다른 키를 돌려줄 수 없도록 prefix를 바이트로 다시 비교), 비밀의 SHA-256 상수 시간 비교 한 번, 만료(`now >= expiresAt`, 유예 없음, `expiresAt`이 null이면 만료 없음), 아래의 보류 표시, 주체 캐시를 거친 소유자 읽기, 소유자의 `enabled`와 `ver`, 엔드포인트 제한(403 `api_key_not_allowed`), 스코프 가드(403 `insufficient_scope`). 소유자는 비밀이 맞은 뒤에만 읽습니다. 실패는 모두 401 `invalid_api_key`이고, store 오류는 보호 경로면 503, 공개 경로면 익명입니다(로그는 1분에 한 번). 해시 슬롯과 실패 응답 하한은 쓰지 않습니다. 키 행은 요청마다 저장소에서 읽고 캐시하지 않으므로 삭제·폐기·만료된 키는 다음 요청부터 모든 노드에서 멈춥니다. 소유자의 `enabled`·`ver`·스코프는 주체 캐시를 거치므로 다른 노드에는 `userCacheTtl`(5초) 안에 닿습니다.

**키 전용 IP 제한**: 401 `invalid_api_key`(형식 오류, 없는 키, 틀린 비밀, 만료, stale, 비활성 소유자, 불변식 위반, 보류 표시)는 보호 경로든 공개 경로든 클라이언트 IP마다(IPv6는 `/64`) 따로 된 카운터에 한 번씩 셉니다. `.apiKeyFailureLimit(max, windowMillis)`(기본 15분에 100번)를 넘으면 키 요청은 보호 경로에서 파싱·조회 전에 429 `too_many_attempts` + `Retry-After`, 공개 경로에서 조회 없이 익명입니다. 성공해도 초기화하지 않고 로그인 제한과 따로입니다(로그인 실패가 키를 막지 않고 키 실패가 로그인 예산을 쓰지 않음). 남는 위험: 같은 IP(NAT나 프록시)에서 누군가 이 제한을 다 쓰면 그 IP에서 보낸 유효한 키도 함께 막힙니다. 키 요청 중 `LoginThrottle`이 실패하면 보호 경로는 503입니다.

**stale 키**: 키는 발급 때 소유자의 `ver`(ownerVer)를 담습니다. ownerVer가 소유자의 `ver`보다 작으면 stale 키(그 뒤 소유자의 토큰이 폐기됨)라 401이고, `deleteStaleByOwner(owner, ver)`를 요청 경로 밖에 예약합니다. auth가 일으킨 `ver` 증가 뒤에 소유자에게 stale 키가 남아 있어도 같은 삭제를 예약합니다. 예약은 소유자마다 많아야 하나이고, 기다리는 동안 본 가장 큰 `ver`로 돌며, 삭제가 시작되면 그 소유자를 예약 목록에서 빼므로 그 뒤에 만난 stale 키는 새로 예약합니다(키 요청이 몰려도 삭제 작업이 쌓이지 않음). ownerVer가 캐시의 `ver`보다 크면 소유자를 한 번 다시 읽고, 그래도 크면 불변식 위반(사용자 저장소의 `ver`가 되돌아갔거나 복제본이 늦음)이라 401이고 ERROR를 1분에 한 번 남기며, `SINGLE_INSTANCE`에서는 그 키를 요청 경로 밖에서 지웁니다(`MULTI_INSTANCE`는 남겨 두므로 복제본이 따라잡으면 다시 인증됨). auth가 일으키는 모든 `ver` 증가(logout `?all=true`, 비밀번호 변경, 관리자 재설정과 비활성화, 복구) 전과 모든 발급 전에 `SINGLE_INSTANCE`는 ownerVer가 저장된 `ver`보다 큰 소유자의 키를 지웁니다(`deleteFutureByOwner`). 이 삭제는 사용자 쓰기 잠금 안에서 잡는 노드 내 소유자별 키 잠금 안에서 돕니다. `ver` 증가 전 삭제가 실패해도 증가는 일어나고, ERROR를 남기며, 메모리의 보류 표시가 재시도가 성공할 때까지(다음 요청·발급·증가·10분 정리 실행) 그 키들을 거절합니다. 보류 표시는 저장하지 않으므로 재시작하면 사라집니다.

키 요청은 소유자로 인증되고 유효 스코프는 "키 스코프가 덮고 소유자의 지금 스코프도 덮는다"입니다. `.allowAdminScopesOnApiKeys`가 꺼져 있으면 키에 남은 `auth-*` 스코프는 판정에서 빠집니다. 자격 증명 엔드포인트(login, refresh, logout, register, setup, `POST /auth/password`, `POST /auth/me/apikeys`, `POST /auth/admin/apikeys`)에 유효한 키를 보내면 스코프 가드보다 먼저 403 `api_key_not_allowed`입니다. 서비스 계정 이름의 계정 잠금이나 로그인 제한은 그 키 인증을 막지 않습니다. 키 요청은 CSRF 토큰이 필요 없습니다. 키로 인증한 동작의 감사 이벤트는 키 id(`apiKeyId`)를 담고, 서비스 계정 행위자는 표시(`serviceAccount`)합니다.

**키의 관리 스코프**: 키는 `auth-*` 스코프를 그 이름(또는 `resource:*`)으로만 덮고, `.allowAdminScopesOnApiKeys(true)` 여부와 상관없이 `*`로는 덮지 않습니다. 어떤 키도 주거나 가진 `*`를 덮지 않습니다(키는 `admin`을 주지 못하고, `*` 보유자를 건드리지 못하고, `*` 그룹에 사람을 넣지 못함). 옵트인이 꺼져 있으면 키에 남은 `auth-*` 스코프는 지우지 않고 무시합니다. 옵트인이 켜져 있으면 doctor가 기동마다 WARN을 냅니다(키가 한 역할 부여와 재활성화는 키를 지워도 남으니 apiKeyId로 감사 이벤트를 검토). 꺼져 있으면 아직 `auth-*` 스코프를 가진 키의 수를 WARN으로 알리고, 저장소가 셀 수 없으면(`countKeysWithAdminScopes()`가 -1) 요청 때 키 id와 함께 1분에 한 번 WARN합니다. `MULTI_INSTANCE`에서 `supportsAtomicLimitInsert()`가 false인 `ApiKeyStore`는 doctor FAIL입니다(앱이 시작하지 않음).

**401·403 챌린지**: auth의 401은 모두 `WWW-Authenticate` 헤더 하나를 답니다. `Bearer`이고, 읽은 JWT가 무효나 만료일 때만 `Bearer error="invalid_token"`이며, 키를 켜면 뒤에 속성 없는 키 스킴이 붙습니다(`Bearer, ApiKey`, `.apiKeyScheme("ak")`면 `Bearer error="invalid_token", ak`). JWT 요청의 403 `insufficient_scope`는 `Bearer error="insufficient_scope"`를 달고, 키 요청의 403(`insufficient_scope`, `api_key_not_allowed`)에는 `WWW-Authenticate`가 없습니다. `invalid_api_key`는 본문 코드로만 알립니다.

Express 대응: `passport-headerapikey`의 `HeaderAPIKeyStrategy`(`Authorization: ApiKey ...` 헤더).

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).apiKeys(new InMemoryApiKeyStore()).apiKeyScheme("ApiKey")
        .maxKeysPerUser(20).build();
auth.install(app);
app.get("/reports", auth.require("reports:read"), (req, res) -> res.send(auth.user(req).getId()));
// curl -H "Authorization: ApiKey nk_Ab3dE5gH_..." https://api.example.com/reports
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).apiKeys(InMemoryApiKeyStore()).build()
auth.install(app)
app.get("/reports", auth.require("reports:read")) { req, res -> res.send(auth.user(req).id) }
```

## API 키 발급: auth.apiKeys()

`auth.apiKeys()`는 `AuthApiKeys`(패키지 `apikey`)를 돌려줍니다. 운영 스크립트나 서비스 계정처럼 앱 코드에서 키를 다루며, 만료 없는 키(`expiresAt` null)를 만드는 유일한 길입니다. **이 파사드는 행위자 검사를 하지 않습니다**(관리 쓰기의 권한 부여 규칙도, 키 행위자 제한도 없음). 위임 관리 화면을 만드는 앱은 행위자를 직접 확인하거나 관리 엔드포인트를 씁니다.

| API | 설명 |
|---|---|
| `IssuedApiKey issue(String ownerUserId, String name, @Nullable Long expiresAt, String... scopes)` | 새 키. 평문은 결과에 이번 한 번만 있습니다. 키는 store에서 읽은 소유자의 지금 `ver`에 묶이고(그 뒤 `ver`가 오르면 낡은 키), `issuedByUserId`는 null. 소유자마다 노드 안 잠금 아래에서 `insertIfUnderLimit`을 부르고, prefix가 겹치면 새 키로 세 번까지 다시 시도한 뒤 `AuthUnavailableException`(503). 없는·비활성 소유자, 스코프 문법([역할과 스코프](#역할과-스코프-rolepolicy-rolestore-scopes))에 어긋나는 스코프, 옵트인 없는 `auth-*` 스코프, 지금 이하의 `expiresAt`은 `IllegalArgumentException`. 한도(`.maxKeysPerUser`)를 넘으면 `AuthRejection(409, "api_key_limit")` |
| `List<ApiKey> list(String ownerUserId)` | 소유자의 모든 키와 상태(`ACTIVE`, `EXPIRED`, `STALE`). 비밀도 해시도 없음. 없는 소유자는 `IllegalArgumentException` |
| `boolean revoke(String keyId)` | 지움. 없으면 예외 없이 false |
| `IssuedApiKey issue(Request req, String ownerUserId, String name, @Nullable Long expiresAt, String... scopes)` | 라우트에서 부르는 `issue`. 동작은 같고, 요청 사용자가 키의 `issuedByUserId`로 기록되며 감사 이벤트에 행위자·요청 id·IP와, 키로 인증한 요청이면 그 키의 `apiKeyId`가 남습니다. 행위자 검사는 이 형태도 하지 않습니다 |
| `boolean revoke(Request req, String keyId)` | 라우트에서 부르는 `revoke`. 동작은 같고, 감사 이벤트에 행위자·요청 id·IP와, 키로 인증한 요청이면 그 키의 `apiKeyId`가 남습니다 |

라우트 안에서는 요청을 받는 형태를 쓰십시오. 예: `app.delete("/my-keys/:id", (req, res) -> res.send(String.valueOf(auth.apiKeys().revoke(req, req.param("id")))))`. `.apiKeys(store)` 없이 부르면 모두 `AuthStateException`입니다. 예외는 `auth.users()`와 같은 집합이고 `auth.errors()`가 JSON으로 바꿉니다. `apikey.InMemoryApiKeyStore`는 단일 인스턴스와 테스트용 메모리 저장소입니다(스레드 안전, prefix는 대소문자를 가리는 유일 키, `AuthTesting.verifyApiKeyStore`를 통과). `MULTI_INSTANCE`는 이것을 `build()`에서 거부합니다. Express 대응: 개인 액세스 토큰 페이지 뒤의 서비스 계층.

```java
Auth keyed = Auth.builder(System.getenv("JWT_SECRET")).apiKeys(new InMemoryApiKeyStore()).build();
String botId = keyed.users().create("ci-bot", null, u -> u.toBuilder().serviceAccount(true).build()).getId();
IssuedApiKey k = keyed.apiKeys().issue(botId, "ci-bot", null, "posts:read");
System.out.println(k.getPlainKey()); // nk_<prefix8>_<secret32>, 다시 볼 수 없음
for (ApiKey key : keyed.apiKeys().list(botId)) {
    System.out.println(key.getPrefix() + " " + key.getStatus());
}
```

```kotlin
// `auth`는 위에서 만든 Auth입니다
// `botId`: 위 Java 블록에서 만든 서비스 계정
val issued = auth.apiKeys().issue(botId, "ci-bot", null, "posts:read")
val expires: Long? = issued.key.expiresAt   // null: 만료 없음
auth.apiKeys().revoke(issued.key.id)
```

## 새 비밀번호 정책: PasswordPolicy와 BreachedPasswordChecker

새 비밀번호(가입, setup, 관리 A2·A4, `auth.users().create`, 비밀번호 변경)는 해시 슬롯을 잡기 전에 `PasswordPolicy`와, 설정했으면 `spi.BreachedPasswordChecker`를 거칩니다. 로그인에는 적용하지 않으므로 더 엄격해진 정책 아래에서도 기존 비밀번호로 로그인됩니다. `PasswordPolicy.of(profile)`(기본): `BASELINE`·`STANDARD` 최소 8자, `STRICT` 15자, 모두 최대 128자(유니코드 코드 포인트). `PasswordPolicy.length(min, max)`로 직접 정합니다(최소 1 이상, 최대 128 이하). 조합 규칙(숫자·기호)은 두지 않습니다(NIST SP 800-63B). 위반은 HTTP 엔드포인트에서 422 `UNPROCESSABLE_ENTITY`(메시지에 이유, 비밀번호는 없음), `auth.users().create`에서 `IllegalArgumentException`입니다. 관리 A2·A4([관리 엔드포인트](#관리-엔드포인트-authadminroutes))의 빈 비밀번호는 지금처럼 400입니다. `BreachedPasswordChecker`(`@FunctionalInterface`, `boolean isBreached(char[] password) throws Exception`)가 true면 같은 위반이고, 던지면 저장하지 않고 해시하지 않으며 503(예외 문구는 응답에 넣지 않음, 로그에만)입니다. auth는 사본을 넘기고 지웁니다. Express 대응: 가입 라우트의 `express-validator` 길이 검사와 `hibp` 조회.

```java
Set<String> commonPasswords = new HashSet<>(Arrays.asList("Password123!", "qwertyuiop12"));
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
        .passwordPolicy(PasswordPolicy.length(12, 128))
        .breachedPasswordChecker(pw -> commonPasswords.contains(new String(pw)))
        .build();
```

```kotlin
// `commonPasswords`: 위 Java 블록의 Set<String>
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .breachedPasswordChecker { pw -> String(pw) in commonPasswords }
    .build()
```

## 비밀번호 인증기: spi.Authenticator

`Authenticator`(패키지 `spi`, `@FunctionalInterface`, `@Nullable AuthUser authenticate(String username, char[] password, AuthEventContext ctx) throws Exception`)는 로그인의 비밀번호 확인(사용자 저장소 + 해시기)을 바꾸는 유일한 공개 seam입니다. Bearer와 API 키 인증기는 auth 내부에 있고 바꿀 수 없습니다. 실패는 모두 null로 돌려주고(없는 이름·틀린 비밀번호·비활성을 가르지 않음), 돌려주는 사용자는 auth 사용자 저장소에 있어야 합니다(토큰은 그 id로 발급하고 요청마다 다시 읽음). 해시 슬롯 안에서 부르고 실패 응답은 실패 응답 하한을 기다리므로 사용자 정의 확인도 실패 시간을 줄이지 못합니다. 예외는 503 JSON이고 로그인이 되지 않습니다. 로그인 제한, 기기 신뢰, 잠금은 그대로입니다. Express 대응: passport `LocalStrategy`의 verify 콜백.

```java
UserStore users = new InMemoryUserStore();
Map<String, String> directory = new HashMap<>(); // stands in for an LDAP bind
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).users(users)
        .authenticator((name, password, ctx) -> new String(password).equals(directory.get(name))
                ? users.findByUsername(name) : null)
        .build();
```

```kotlin
// `store`: 앱의 UserStore, `directory`: LDAP 바인드를 대신하는 Map<String, String>(위 Java 블록과 같음)
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .users(store)
    .authenticator { name, password, _ -> if (String(password) == directory[name]) store.findByUsername(name) else null }
    .build()
```

## API 키 발급 엔드포인트: PAT와 관리 발급

`.apiKeys(store)`를 켜면 사용자가 직접 개인 액세스 토큰(PAT)을, 관리자가 다른 사용자(주로 서비스 계정)의 키를 발급합니다. 둘 다 JWT 로그인만 받는 주체 필요 자격 증명 엔드포인트입니다(만료된 토큰은 401 `invalid_token`과 `WWW-Authenticate: Bearer error="invalid_token", ApiKey`, 쿠키 세션은 `X-CSRF-Token`과 Origin이 맞아야 하고 아니면 403 `csrf_failed`, API 키는 403 `api_key_not_allowed`). 키로는 키를 만들 수 없습니다.

| 엔드포인트 | 본문 | 성공 |
|---|---|---|
| `POST /auth/me/apikeys` | `{"name","scopes","expiresInDays"?,"password"}` | 201 `{"id","prefix","name","ownerUserId","scopes","expiresAt","createdAt","lastUsedAt","issuedByUserId","status","key"}`. `key`가 평문 키이고 다시 볼 수 없습니다(`Cache-Control: no-store`) |
| `POST /auth/admin/apikeys` | `{"ownerUserId","name","scopes","expiresInDays"?,"password"}` | 같은 201. `password`는 **호출자 자신의** 지금 비밀번호 |
| `GET /auth/me/apikeys` / `DELETE /auth/me/apikeys/:id` | — | 200 내 키 목록(201과 같은 모양에서 `key`만 없음) / 204. 남의 키 id와 없는 id는 모두 404 `NOT_FOUND`(키가 있는지 드러내지 않음). 삭제에는 비밀번호가 필요 없습니다 |

**판정 순서**(앞 단계가 답함, 거절된 요청은 해시 슬롯도 시도 횟수도 쓰지 않음):

1. 본문 검사(모두 Core `HttpException(400)`, 코드 `BAD_REQUEST`): 관리 발급은 문자열 `ownerUserId`, 1–200자 문자열 `name`, 비지 않은 문자열 배열 `scopes`(스코프 문법을 따르고, `auth-*`는 `.allowAdminScopesOnApiKeys(true)`일 때만), 선택 `expiresInDays`(JSON 정수 1–`.maxApiKeyDays`. 없으면 그 최대값, `"30"`·`1.5`·`0`은 400), 비지 않은 문자열 `password`(없거나 `null`이면 400).
2. 관리 발급만: 없는 소유자 404 → 권한 부여 규칙(새 키의 스코프를 호출자가 덮고 소유자의 스코프도 덮어야 함, 403 `insufficient_scope`) → 비활성 소유자 400. PAT는 호출자가 요청한 스코프를 모두 가져야 합니다(403 `insufficient_scope`).
3. 비밀번호 확인: `POST /auth/password`·`auth.users().checkPassword`와 공유하는 사용자별 한도(15분에 5번 실패, 넘으면 해시 없이 429 `too_many_attempts` + `Retry-After`), 해시 슬롯 하나에서 호출자를 다시 읽고 PBKDF2 한 번(해시가 null인 SSO 사용자도 더미 해시로 같은 일). 틀리면 401 `invalid_credentials`이고 실패 응답 하한(1.25T + 지터) 뒤에 나갑니다. 맞으면 지연이 없습니다. 맞았지만 그 사이 호출자가 지워졌거나 비활성이면 401 `invalid_token`입니다. 한 사용자의 비밀번호 확인(`POST /auth/password`, 두 발급 엔드포인트, `auth.users().checkPassword`)은 한 번에 하나만 돕니다. 확인이 도는 동안 같은 사용자의 다른 엔드포인트 요청은 429 `too_many_attempts`와 `Retry-After: 1`이고, `checkPassword`는 최대 5초 기다립니다(병렬 요청으로 한도를 앞지르지 못하게).
4. 발급: 소유자별 노드 내 잠금 아래에서 `insertIfUnderLimit`. 살아 있는 키가 `.maxKeysPerUser`(기본 20)이면 409 `api_key_limit`(만료된 키와 ownerVer가 지금 `ver`보다 작은 stale 키는 세지 않음. `MULTI_INSTANCE`에서 ownerVer가 읽은 `ver`보다 커서 stale로 보이는 키는 셈). 키의 ownerVer는 PAT면 비밀번호 확인이 읽은 호출자 행, 관리 발급이면 2단계에서 읽은 소유자 행의 `ver`이고, `issuedByUserId`와 `api_key_issued` 감사 이벤트의 행위자는 호출자입니다. `expiresAt`은 발급 시각 + `expiresInDays`일입니다.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET")).apiKeys(new InMemoryApiKeyStore()).maxApiKeyDays(90).build();
auth.install(app);
// POST /auth/me/apikeys  Authorization: Bearer <login token>
// {"name":"ci","scopes":["posts:read"],"expiresInDays":30,"password":"<my password>"}
// -> 201 {"id":"...","prefix":"Ab3dE5gH","status":"active","key":"nk_Ab3dE5gH_..."}
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET")).apiKeys(InMemoryApiKeyStore()).build()
auth.install(app)
// fetch("/auth/admin/apikeys", {method: "POST", headers: {Authorization: "Bearer " + adminToken},
//   body: JSON.stringify({ownerUserId: botId, name: "ci-bot", scopes: ["posts:read"], password: myPassword})})
```

키 엔드포인트의 삭제(`DELETE /auth/me/apikeys/:id`, `DELETE /auth/admin/apikeys/:id`)와 `auth.apiKeys().revoke`는 `api_key_revoked` 감사 이벤트를 남깁니다. 지운 키는 `revokedApiKeyId`, 대상은 소유자이고, 발급자가 기록된 키면 `issuedByUserId`도 남습니다. API 키 행위자가 지웠으면 그 행위자의 키가 `apiKeyId`입니다. 지우기 전에 키를 읽지 못해도 철회는 진행되고 그 이벤트에는 `issuedByUserId`가 없으며, `auth.apiKeys().revoke`는 대상도 없습니다. 요청을 받지 않는 `auth.apiKeys().revoke(keyId)`의 이벤트에는 행위자, 요청 id, IP, `apiKeyId`가 없고 라우트 안에서 불러도 같습니다. 이것들을 남기려면 `auth.apiKeys().revoke(req, keyId)`를 쓰십시오(`issue`도 같음).

Express 대응: GitHub의 "개인 액세스 토큰 만들기"처럼 비밀번호를 다시 묻는 발급 라우트.

## 파일 비밀 값 암호화: AuthFileKey

`FileUserStore`와 `KeySources.fromFile`에 `AuthFileKey`(마스터 키)를 주면 두 파일 저장소가 쓰는 비밀 값을 `enc(<payload>)`로 저장합니다. payload는 형식 버전 1바이트(1), `SecureRandom`의 96비트 난수 IV, AES-256-GCM 암호문과 128비트 태그를 이어 붙인 것의 패딩 없는 base64url입니다. 암호화 키는 마스터 키에서 용도별로 HMAC-SHA256으로 파생하고, 용도와 레코드(사용자 id, 키 파일 이름)를 추가 인증 데이터로 묶으므로 다른 레코드나 다른 용도로 옮긴 값은 열리지 않습니다. `javax.crypto`만 쓰므로 Java 8과 Android API 21에서 그대로 돌고 새 의존성이 없습니다. Express 대응: `process.env`에서 읽은 비밀로 내용을 암호화하는 세션 저장소(`express-session` 저장소의 `secret`)와 같은 자리입니다.

- **암호화하는 값:** `FileUserStore` 사용자 파일의 각 `passwordHash`(오프라인 크래킹 대상), `KeySources.fromFile` 키 파일의 서명 키(있으면 토큰을 위조할 수 있음).
- **평문으로 두는 값과 이유:** refresh 기록의 `tokenHash`(256비트 난수 토큰의 SHA-256이라 되돌릴 수도 제시할 수도 없음), 로그인 제한 키(사용자·IP의 SHA-256 식별자와 실패 시각, 자격 증명이 아님), 기기 신뢰의 `tokenId`와 카운터(서명된 토큰의 식별자, 위조하려면 서명 키가 필요), 그룹 파일(비밀 없음), 사용자 행의 `id`·`username`·`ver`·`enabled`·`roles`·`serviceAccount`(식별자와 상태). `attributes`는 앱이 정하는 값이라 그대로 씁니다. 비밀은 `attributes`에 넣지 마세요.

| API | 설명 |
|---|---|
| `AuthFileKey.of(byte[])` | 인자를 복사합니다. 32바이트보다 짧으면 `IllegalArgumentException` |
| `AuthFileKey.of(String)` | 표준 또는 URL-safe base64(패딩은 있어도 없어도 되고 앞뒤 공백은 무시). 32바이트 이상으로 풀리지 않으면 규칙만 적고 입력은 담지 않은 `IllegalArgumentException` |
| `AuthFileKey.fromEnv()` | 환경 변수 `NARU_AUTH_FILE_KEY`(`AuthFileKey.ENV_VARIABLE`)를 지금 읽습니다. 없거나 비면 `null`, 잘못된 값이면 변수 이름만 적은(값은 적지 않음) `IllegalArgumentException` |
| `new FileUserStore(file, key)`, `new FileUserStore(file, maxFileBytes, logger, key)` | 키가 `null`이면 `new FileUserStore(file)`, `new FileUserStore(file, maxFileBytes, logger)`와 같습니다. 다른 `FileUserStore` 생성자처럼 파일을 읽고 `IOException`(checked)을 던지므로 감싼 메서드가 선언하거나 잡습니다 |
| `KeySources.fromFile(file, key)` | 키가 `null`이면 `KeySources.fromFile(file)`과 같습니다 |

키 만들기: 32바이트 난수를 base64로 만들어 데이터 디렉터리 밖에 둡니다. 예를 들어 `openssl rand -base64 32`의 출력을 `NARU_AUTH_FILE_KEY`에 넣습니다. `AuthFileKey`의 `toString()`은 고정 문자열이고 `Serializable`이 아니며, 마스터 키와 파생 키는 로그·예외 메시지·파일 어디에도 쓰지 않습니다.

키를 준 `FileUserStore` 옆에 auth가 기본값으로 만드는 refresh·로그인 제한·기기 신뢰 파일 저장소는 같은 키를 이어받습니다(빌더에 키를 따로 넣는 설정은 없습니다). 이 세 파일에는 비밀 값이 없으므로 필드와 형식 버전은 그대로입니다.

- **PROD에서** 키 없는 `FileUserStore`나 키 없는 `KeySources.fromFile`이 있으면 `build()`가 키가 빠진 옵션(`new FileUserStore(file, AuthFileKey)`, `KeySources.fromFile(file, AuthFileKey)`)과 `NARU_AUTH_FILE_KEY`를 적은 `AuthConfigException`을 던집니다. `KeySources.fromFile`에 키가 없으면 그 키 파일은 읽지도 만들지도 않습니다. 두 파일 저장소를 쓰지 않는 Auth(예: `InMemoryUserStore`와 `KeySources.fromEnv`)는 영향이 없습니다.
- `ForwardingUserStore` 데코레이터(여러 겹이어도) 안의 `FileUserStore`도 맨 `FileUserStore`와 똑같이 봅니다. PROD 거부, DEV WARN, doctor 항목 `plain secret values in auth files`, 파일 상태 검사가 감싼 `FileUserStore`에도 적용됩니다. refresh·로그인 제한·기기 신뢰 파일 기본값은 지금처럼 맨 `FileUserStore`일 때만 만듭니다.
- **DEV에서** 키가 없으면 `build()`는 성공하고, 평문으로 쓰는 파일과 `NARU_AUTH_FILE_KEY`를 적은 WARN을 `build()`마다 한 번 남깁니다. 저장소는 지금처럼 씁니다: 사용자 파일은 형식 버전 1이고 `passwordHash`는 평문 해시 문자열, 키 파일은 원시 32바이트입니다.
- **처음 읽을 때 옮겨 쓰기:** 키를 준 저장소가 평문 파일(형식 버전 1인 사용자 파일, 원시 바이트 키 파일)을 처음 읽으면 그 파일의 평문 비밀 값을 모두 `enc(…)`로 바꿔 한 번에 원자적으로 다시 씁니다(사용자 파일은 [저장 파일의 내구성](#저장-파일의-내구성) 규약, 키 파일은 만들 때와 같은 임시 디렉터리 후 이름 바꾸기 경로. `.bak`·임시 파일이 남지 않음. 이름 바꾸기가 기존 파일을 덮지 못하는 Windows에서는 키 파일을 잠시 `.<이름>.bak`으로 옮겼다가 새 파일을 놓으므로, 그 사이에 멈추면 다음 읽기가 새 키를 만들지 않고 그 백업을 되살립니다. 새 파일을 놓은 뒤 원시 키가 든 `.bak`을 지우지 못하면(파일이 잠김) `build()`는 실패하지 않고, 옮긴 키를 쓰며 키 값 없이 파일 이름만 적은 WARN을 한 번 남깁니다. 그 `.bak`은 암호화된 키 파일을 다음에 읽을 때 지웁니다). 사용자 파일은 형식 버전 2가 되고 `null` 해시는 `null`로 남습니다. 서명 키 바이트는 그대로이므로 이미 발급한 토큰도 계속 검증됩니다. 키 파일의 값은 파일 이름에 묶이므로 암호화된 키 파일은 같은 이름으로 두고 옮기거나 되살립니다(다른 이름이면 열리지 않음). 옮겨 쓸 때 사용자 파일은 암호화한 해시 수를 적은 INFO를 한 번 남깁니다.
- 옮겨 쓴 파일을 키 없이 열면(DEV든 PROD든) 파일에 암호화된 값이 있고 `AuthFileKey`가 필요하다는 `IOException`으로 실패하고 파일은 바뀌지 않습니다. 형식 버전 1만 아는 판은 버전 2 파일을 모르는 버전으로 거부하므로 `enc(…)`를 해시로 읽지 않습니다.
- 값이 열리지 않으면(바뀐 바이트, 다른 키, 모르는 버전, 깨진 payload, 다른 레코드에서 옮긴 값) 사용자 파일은 파일과 필드 `passwordHash`를 적은 `FAIL` `IOException`으로 생성이 실패하고(기동이 멈춤) 파일은 덮어쓰지 않습니다. 틀린 비밀번호로 처리하지 않습니다. 키 파일이 열리지 않으면 파일을 적은 오류로 `build()`가 실패하고 새 키를 만들지 않습니다.
- doctor 항목 `plain secret values in auth files`: 키 없는 `FileUserStore`의 `null`이 아닌 해시 수와 원시 키 파일 수를 더한 값입니다. 0보다 크면 WARN, 0이면 INFO 줄(`auth doctor: plain secret values in auth files: 0`)이고, 두 파일 저장소를 쓰지 않으면 이 항목이 없습니다.
- 키 회전은 0.11.0에서 지원하지 않습니다. 파일을 새 키로 다시 암호화하는 공개 메서드가 없고, 다른 키로 열면 위처럼 실패합니다. payload의 버전 바이트는 나중의 회전 형식을 위해 남겨 둔 자리입니다.

```java
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.AuthFileKey;
import com.snoworca.naruserve.utils.auth.KeySources;
import com.snoworca.naruserve.utils.auth.user.FileUserStore;
import java.io.File;

class FileSecrets {
    static Auth build() throws Exception {         // new FileUserStore(...)는 파일을 읽으므로 IOException
        AuthFileKey key = AuthFileKey.fromEnv();   // NARU_AUTH_FILE_KEY, 없으면 null(PROD는 build()가 거부)
        File dataDir = new File("/var/lib/app");
        return Auth.builder(KeySources.fromFile(new File(dataDir, "naru-auth.key"), key))
                .users(new FileUserStore(new File(dataDir, "users.json"), key))
                .build();
    }
}
```

```kotlin
// `dataDir`: 데이터 디렉터리, 위 Java 블록의 File("/var/lib/app")
val key: AuthFileKey? = AuthFileKey.fromEnv()
val auth = Auth.builder(KeySources.fromFile(File(dataDir, "naru-auth.key"), key))
    .users(FileUserStore(File(dataDir, "users.json"), key))
    .build()
```

## 용어

이 문서에 나오는 auth 용어입니다. 마지막 열은 Express와 passport에서 비슷한 것을 부르는 이름입니다.

| 용어 | 뜻 | Express에서 |
|---|---|---|
| **principal(주체)** | 요청을 보낸 인증된 사용자. `auth.user(req)`가 돌려주는 `AuthPrincipal`이고 id, 이름, 역할, 스코프를 가집니다 | `req.user` |
| **scope(스코프)** | 할 수 있는 일 하나. `resource:action` 꼴(예: `posts:write`)이고 `auth.require(...)`가 검사합니다 | `express-jwt-authz`의 scope |
| **role(역할)** | 스코프 묶음의 이름(예: `editor`). 사용자와 그룹에 주고, `RolePolicy`나 `RoleStore`가 스코프로 풉니다 | `accesscontrol`의 role |
| **grant(부여)** | 주는 쪽의 스코프, 곧 역할이 주는 스코프. `posts:*`, `*` 같은 와일드카드는 여기에만 씁니다. [관리 쓰기의 권한 부여 규칙](#관리-쓰기의-권한-부여-규칙)은 대상이 얻는 스코프를 행위자의 스코프가 모두 덮어야 한다는 규칙입니다 | `accesscontrol`의 `grant(...)` |
| **family(계열)** | 로그인 한 번에서 시작한 refresh 토큰의 사슬(`fam` 클레임). refresh마다 새 토큰이 같은 계열을 잇고, 이미 쓴 토큰이 다시 오면 계열 전체를 폐기합니다 | refresh 토큰 회전의 token family |
| **ver(토큰 버전)** | 사용자마다 저장하는 숫자. 전체 로그아웃, 비밀번호 변경, 비활성화, 복구가 1 올리고, 토큰의 `ver`가 다르면 401 `invalid_token`입니다 | 사용자 행의 `tokenVersion` 열 |
| **kid(키 id)** | 토큰 헤더에 적힌 서명 키의 이름. 키를 회전해도 kid로 옛 토큰을 검증할 키를 찾습니다([키 세트와 회전](#키-세트와-회전-keysource-jwtkeys-keysources)) | JWKS의 `kid` |
| **doctor(시작 점검)** | 기동할 때 설정을 검사하는 단계. FAIL이면 앱이 시작하지 않고 WARN은 로그만 남깁니다([시작 점검](#시작-점검doctor)) | 기동 스크립트의 설정 검사 |
| **DEV와 PROD** | `AuthMode`의 두 모드. DEV는 로컬 개발용으로 검사를 완화하고(임시 키, 평문 허용), PROD는 모두 지킵니다([AuthMode](#authmode)) | `NODE_ENV=development` / `production` |
| **DAG** | 방향이 있고 순환이 없는 그래프. 그룹은 부모를 여럿 가질 수 있지만 순환은 409 `group_cycle`로 거부합니다([그룹](#그룹-groupstore와-grouplimits)) | 그룹 테이블의 부모 간선 |
| **CIDR** | `10.0.0.0/8`처럼 주소 범위를 적는 표기. `app.trustProxyCidrs(...)`는 이 범위의 프록시를 믿습니다 | `app.set('trust proxy', '10.0.0.0/8')` |
| **hop(홉)** | 요청이 거친 프록시 한 단계. `app.trustProxyHops(1)`은 바로 앞의 프록시 하나를 믿습니다 | `app.set('trust proxy', 1)` |
| **PBKDF2** | 기본 비밀번호 해시 방식. 반복 횟수(비용)를 기기 속도에 맞춰 정합니다([비밀번호 해시](#비밀번호-해시-passwordhasher-hashcost)) | `crypto.pbkdf2` |
| **A1–A6, G1–G9** | 관리 엔드포인트의 짧은 이름. A는 사용자, G는 그룹 엔드포인트입니다. G9는 v2용으로 남겨 두어 등록하지 않습니다([관리 엔드포인트](#관리-엔드포인트-authadminroutes)) | 관리용 `express.Router()`의 라우트 |

Express 대응: 위 표의 마지막 열입니다. 아래는 같은 용어를 코드에서 본 모습입니다.

```java
Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
    .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())   // role editor가 주는 grant 둘
    .build();
app.use(auth);
app.get("/me", (req, res) -> {
    AuthPrincipal p = auth.user(req);                                                   // principal
    res.send(p.getUsername() + " " + p.hasRole("editor") + " " + p.hasScope("posts:write"));
});
```

```kotlin
val auth = Auth.builder(System.getenv("JWT_SECRET"))
    .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())
    .build()
app.use(auth)
app.get("/me") { req, res ->
    val p = auth.user(req)
    res.send(p.username + " " + p.hasRole("editor") + " " + p.hasScope("posts:write"))
}
```
