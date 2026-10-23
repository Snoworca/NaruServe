# `start` API 종합과 권장안

조사 기준: 2026-10-23. 이 문서는 세 조사를 종합한 **권장안**이다. 코드·SRS는 바꾸지 않았고, 확정은 SRS 요구사항 작성 단계에서 한다.

| 문서 | 내용 |
|---|---|
| [01-prior-art.md](01-prior-art.md) | Spring Boot·Javalin·Ktor·Micronaut·Quarkus·Helidon·Spark·Express 등 선행 API 비교 |
| [02-naruserve-internals.md](02-naruserve-internals.md) | 현재 생명주기, 배치 후보, 고정 테스트·AC 충돌, 필요한 SRS 변경 |
| [03-tinyhttpd-and-junior-dx.md](03-tinyhttpd-and-junior-dx.md) | TinyHttpd 3.0.0 설정 대응, 주니어 실패 시나리오, 기본값 정책, Android·Kotlin |

## 1. 결론

Spring Boot의 `SpringApplication.run(Class, args)`는 클래스 스캔이 시작점이라 NaruServe의 "자동 스캔 없음"·Android 규칙과 맞지 않는다. **"정적 메서드 하나로 뜬다"는 모양만 가져오고 스캔은 가져오지 않는다.**

```java
// Java: 본문 1줄
public static void main(String[] args) {
    RestApp.start(3000, app -> app.get("/hello", (req, res) -> res.send("Hello World")));
}
```
```kotlin
// Kotlin: 후행 람다 (Kotlin 2.2.0에서 컴파일 확인, 03 §6)
fun main() {
    RestApp.start(3000) { app -> app.get("/hello") { _, res -> res.send("Hello World") } }
}
```

`RestApp.start(int port, Configurer<RestApp> routes)`를 `NaruServe-api`의 `RestApp` static 메서드로 추가하고 `ServerHandle`을 반환한다. 내부 순서는 `create()` → 설정 람다 → `listen(port)` → 시작 로그 → (JVM이면) shutdown hook이다.

## 2. 결정별 권장과 근거

| # | 결정 | 권장 | 근거 |
|---|---|---|---|
| 1 | 위치 | `RestApp` static (Core) | `create()`가 이미 ServiceLoader로 엔진 경계를 넘으므로 Core 금지 목록에 걸리지 않는다(02 §1). `NaruServe` 모듈의 새 클래스는 발견성이 떨어지고, 둘 다 두면 같은 일을 하는 API가 둘이 된다. |
| 2 | 블로킹 | 비블로킹 | `listen`이 이미 비블로킹이고 non-daemon keeper 스레드가 JVM을 붙든다(실행으로 확인, 03 시나리오 2). 블로킹이면 `start(0)`·테스트·Android에서 핸들을 못 받는다. 대기가 필요하면 `ServerHandle.await()`. |
| 3 | 반환 | `ServerHandle` + 종료 메서드 추가 | `RestApp`을 반환하면 이미 RUNNING인 앱이라 `app.get()`이 예외가 되는 함정이 있고 FR-APP-002 AC-5 등을 깬다(02 §3.2). 핸들에 `shutdown(long, TimeUnit)`·`url()`·`await()`를 추가하는 안이 가장 적게 깬다. 이름은 (미확정). |
| 4 | 포트 | **v1은 명시 포트만** `start(int, Configurer)` | 02와 03이 갈린 지점이다. 포트 생략 오버로드는 기본값을 8080(object.md)과 3000(README·docs·examples) 중 골라야 하고 `PORT` 환경변수는 숨은 입력원이다. Express `listen(3000)`과 같은 모양이 가장 자연스럽고, 생략형은 필요해질 때 추가해도 호환이 깨지지 않는다. |
| 5 | 바인드 | `127.0.0.1` 유지 | FR-ENGINE-002를 약화하지 않는다. LAN 노출은 람다 안에서 `app.server(o -> o.bindAddress("0.0.0.0"))`. 환경변수로 바인드를 바꾸는 입력원은 만들지 않는다. 시작 로그 힌트로 "다른 기기에서 안 붙는다"를 줄인다. |
| 6 | shutdown hook | **JVM에서 자동 등록, Android는 건너뜀** | 현재 SIGTERM에서 처리 중 요청이 끊기고 `onStop`이 실행되지 않음을 실행으로 확인했다(03 시나리오 5). 숨은 부작용 대신 "`listen`은 순수, `start`는 편의"로 구분하고 문서 한 곳에 대비표를 둔다. 정상 종료·시작 실패·hook 자신이 부른 종료 세 경로에서 hook을 제거하고 테스트한다(02 위험 4). |
| 7 | 시작 로그 | `start`에서만 INFO 한 줄 | 현재는 시작 로그가 없어 터미널이 멈춘 것처럼 보인다. `Listening on http://127.0.0.1:3000 (this device only)`. `listen` 본체에는 넣지 않아 기존 사용자·테스트를 건드리지 않는다. |
| 8 | 설정 람다 예외 | 앱을 `shutdown`으로 정리하고 원 예외를 그대로 던짐 | 로거가 열린 채 남는 것을 막는다. 예외를 삼키지 않는다. |
| 9 | `AutoCloseable` | 보류 | `try (ServerHandle h = start(...)) {}`를 장기 서버에 쓰면 블록 끝에서 바로 종료된다. 이득보다 실수 위험이 크다. |
| 10 | `startAsync`(Android 비동기) | 보류 | 필수 아님. Android는 UI 스레드에서 부르지 말라는 경고를 문서화한다(lifecycle.md와 동일). |

기각한 안: 블로킹 `run`(핸들 없음, Android 부적합), `start(Class)` 스캔형(Android·"자동 스캔 없음" 방침 위반), 정적 전역 DSL(앱이 JVM당 하나로 고정), 인스턴스 `app.start(port)`(`listen`과 이름이 공존해 혼동. 차선책).

## 3. 먼저 처리할 것

1. **TinyHttpd 3.0.0 좌표 정렬.** TinyHttpd가 `com.snoworca.tinyhttpd:*:0.1.0-SNAPSHOT`에서 `com.snoworca:*:3.0.0`으로 바뀌어 지금 빌드가 깨진다(직접 재현, 03 시나리오 10). 이게 안 되면 주니어의 0단계가 막힌다.
2. **SRS 먼저.** CLAUDE.md 규칙상 요구사항이 없으면 구현할 수 없다. 최소한 다음이 필요하다.
   - `docs/spec/16.developer-experience.srs.md`에 새 FR: `start`, `ServerHandle` 확장, 시작 로그, hook.
   - 고정 AC 갱신: `RestApp` static이 `[builder, create]`뿐이라는 문구(IR-ENGINE-001 AC-7, IR-STATIC-002 AC-5, FR-APP-002 AC-5, IR-APP-002 AC-2)와 `ServerHandle`이 정확히 3개라는 IR-APP-005 AC-3. 해당 단언 테스트는 `HttpApiSurfaceTest`, `AppLifecycleApiTest.java:197`, `DefaultRestAppTest.java:271-284`.
   - wave 7~8 SRS의 같은 계열 문구는 전수 확인하지 못했다. 요구 작성 전에 grep으로 확인한다.
3. **구현은 TDD.** 실패 테스트(Java·Kotlin 둘 다, T11) → 구현 → `docs/api/`(app.md, lifecycle.md, express-migration.md, README.md, defaults.md) 갱신 → 마지막에 README Quick start 교체(OPS-REL-001 AC-2, `ReadmeDocsTest` 영향).
4. **별도 개선(이번 `start`와 독립):** 포트 충돌 메시지에 포트·주소 포함, 421로 거절된 Host를 호스트당 1회 WARN으로 기록(03 §3 시나리오 1, 4).

## 4. 열린 질문 (구현 중 결정 가능한 것과 아닌 것)

- 구현하면서 정할 것(미확정, 메모리 규칙상 질문 없이 결정): `ServerHandle` 종료 메서드의 정확한 이름과 timeout 기본값(권장 5초), `url()` 문자열 규칙(와일드카드면 `localhost`), Android 판별 코드를 api 내부에 둘지 SPI로 뺄지.
- **사용자 결정이 필요한 것:** 포트 생략 오버로드와 `PORT` 환경변수를 v1에 넣을지(권장: 넣지 않음). 넣으면 Spring Boot 같은 `start(app -> ...)`가 되지만 숨은 입력원과 기본 포트 선택이 생긴다.

## 5. 확인하지 못한 것

- Android 실기기 동작, `./gradlew build` 전체 회귀, IDE 정지 버튼 동작(03 §8).
- Micronaut·Quarkus 일부 설정, Express 공식 문서의 `app.listen` 절(01에 "확인 못함"으로 표시).
- Kotlin 컴파일은 stand-in Java 인터페이스로 확인했다. 실제 `RestApp.start`로는 구현 후 T11에서 확인한다.
- 세 문서의 "확인"·"추정" 표기는 각 조사자가 붙인 것이고, 이 종합에서 코드를 다시 실행해 재검증하지는 않았다.
