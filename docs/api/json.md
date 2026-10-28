# JSON

[English](en/json.md) | [문서 목록](README.md)

Express의 `express.json()`과 `res.json()`에 해당합니다. NaruServe는 JSON 처리를 `JsonCodec` 인터페이스 뒤에 두고, 기본 구현으로 **Gson 2.14.0**을 씁니다.

## 읽기와 쓰기

```java
User user = req.body(User.class);                          // request to object
List<User> users = req.body(new TypeRef<List<User>>() {});  // generic type
res.json(users);                                            // object to response
ws.sendJson(user);                                          // WebSocket
```

- `req.body(...)`는 `Content-Type`이 JSON일 때만 읽습니다. 본문이 JSON `null`이면 `null`을 돌려줍니다. 오류와 한도는 [Request](request.md#json).
- `TypeRef<T>`는 `List<User>` 같은 제네릭 타입 정보를 보존합니다. 끝의 `{}`(익명 클래스)를 빼먹지 마세요.
- Kotlin의 `object : TypeRef<List<String>>() {}`는 Java의 `new TypeRef<List<String>>() {}`와 같은 `List<String>`을 담습니다. Kotlin은 선언 위치 변성 때문에 `List<? extends String>`을 기록하지만, `TypeRef`가 Kotlin 클래스(`kotlin.Metadata`가 남아 있을 때)에서는 `? extends` 와일드카드를 떼어 냅니다. R8이 이 어노테이션을 지운 빌드에서는 와일드카드가 남지만 기본 Gson 코덱은 두 타입을 똑같이 읽습니다.
- `res.json`은 객체, 배열, `Map`, 문자열, 숫자, `null`을 모두 올바른 JSON으로 씁니다.

타입을 지정하는 요청 읽기와 명시적 `res.json` 쓰기는 0.10.0 공통 HTTP 체크아웃에서도 유지됩니다. 위 두 요청 읽기는 같은 본문을 서로 다른 타입으로 연속해서 읽는 예제가 아니라 선택지입니다. 파싱·한도 오류는 이후에 등록된 오류 처리기로 전달되고, 처리되지 않은 공통 HTTP 오류는 자동 JSON envelope가 아니라 Java 예외 정보를 이스케이프한 HTML입니다. JSON API가 필요하면 오류 미들웨어에서 `res.status(...).json(...)`으로 명시하세요. 아래 공개 좌표는 여전히 0.9.0이며 0.10.0이 이미 출판됐다는 뜻이 아닙니다.

async 라우트가 반환한 미완료 `Deferred`는 응답 완료 후에도 같은 캐시 본문이나 미소비 입력을 보유할 수 있습니다. 생산자의 마지막 접근을 마친 뒤 결과를 확정하고 취소된 입력은 재사용하지 마세요. [요청 소유권](lifecycle.md#지연-콜백과-요청-자원)을 참고하세요.

## DTO 작성

완성된 [Java](../../examples/java/src/main/java/example/JavaExample.java)·[Kotlin](../../examples/kotlin/src/main/kotlin/example/KotlinExample.kt) 예제는 기본 Gson이 생성할 수 있는 DTO를 쓰며 누락·null·공백 이름을 400으로 거부합니다. `Unsafe`를 켜서 생성자를 우회하지 않습니다. Kotlin의 immutable data class를 직접 바인딩하려면 Kotlin 생성자와 nullability를 이해하는 코덱을 명시적으로 제공하세요.

```java
public class User {
    @SerializedName("id")   private String id;
    @SerializedName("name") private String name;

    public User() {}   // no-argument constructor
    // getter/setter
}
```

- **기본 생성자**를 두세요. 기본 Gson 설정은 생성자를 우회하는 인스턴스 생성(`Unsafe`)을 끕니다.
- Android Release 빌드(R8/ProGuard)는 필드 이름을 바꿉니다. `@SerializedName`을 붙이거나 keep 규칙을 추가하세요. 안 하면 JSON 키가 `a`, `b`처럼 바뀝니다.
- 신뢰하지 않는 JSON의 클래스 이름으로 객체를 만드는 다형성 자동 바인딩은 하지 않습니다.
- DB 엔티티 같은 도메인 객체를 바로 바인딩하지 말고 요청용 DTO를 따로 두고 검증하세요.

## 기본 Gson 설정

기본 코덱은 아래와 같은 Gson으로 만듭니다.

```java
Gson gson = new GsonBuilder()
    .setStrictness(Strictness.STRICT)
    .disableJdkUnsafe()
    .create();
app.jsonCodec(new GsonJsonCodec(gson));
```

`GsonJsonCodec`은 `NaruServe-api`에 들어 있지 않습니다(공개 API는 Gson에 의존하지 않습니다). `GsonJsonCodec`은 `NaruServe-gson` 모듈에 있습니다. 이 모듈은 Gson을 `api`로 노출하므로 `Gson`을 직접 만들 때 따로 의존성을 넣지 않아도 됩니다. `RestApp.create()`용 bootstrap 모듈 `com.snoworca.naruserve:naruserve`는 이 모듈을 `api`로 함께 가져오므로, `NaruServe`를 넣었다면 따로 추가할 것이 없습니다(`NaruServe-kotlin`, `NaruServe-android`도 같습니다). `NaruServe` 없이 `RestApp.builder()`로 조립할 때만 아래 의존성을 넣으세요.

```groovy
implementation 'com.snoworca.naruserve:naruserve-gson:0.9.0'
// transitive Gson dependency: com.google.code.gson:gson:2.14.0
```

날짜 형식, 이름 규칙 등을 바꾸려면 Gson을 직접 만들어 `app.jsonCodec(...)`으로 넘깁니다. 위 두 보안 설정은 유지하세요.
직접 만든 `Gson`의 strictness가 `Strictness.LEGACY_STRICT`(strictness를 정하지 않은 Gson의 기본값)이면 `GsonJsonCodec`이 읽을 때 `STRICT`로 올립니다. 느슨한 기본값이 주석이나 따옴표 없는 이름 같은 비표준 JSON을 받아들이지 않게 하려는 것입니다. 일부러 `LENIENT`로 만든 Gson은 그대로 둡니다.
쓸 때는 Gson의 기본 HTML 이스케이프(`<`를 `\u003c`로)를 켜 둡니다. 응답 JSON이 HTML 안에 끼워져도 태그로 해석되지 않게 하려는 것입니다. 끄려면 `disableHtmlEscaping()`을 부른 Gson을 넘기세요.

## 한도

| 한도 | 기본값 | 최대값 | 넘으면 | 설정 |
|---|---|---|---|---|
| 요청 JSON 크기 | 1 MiB | 1 GiB | `413` | `app.jsonLimit(bytes)` |
| 중첩 깊이 | 64 | 255 | `400` | `new GsonJsonCodec(gson, maxDepth)` |

- 크기 한도는 앱 설정입니다. Express의 `express.json({ limit })`에 해당하며 바이트 수를 받습니다. 범위 밖이면 `IllegalArgumentException`(`jsonLimit must be in 1..1073741824 bytes: <n>`)을 던지고, 다른 설정처럼 `listen()` 전에만 부를 수 있습니다.
- 깊이 한도는 코덱 설정입니다. 직접 만든 `Gson`을 넘길 때도 `new GsonJsonCodec(gson)`은 64를 쓰고, 다른 깊이는 생성자의 두 번째 인자로 정합니다. 한도가 코덱에 있는 이유는 다른 `JsonCodec` 구현마다 깊이를 세는 방법이 다르기 때문입니다.
- 값 뒤에 다른 데이터가 있거나(`{} {}`) 빈 본문이면 `400`입니다. 빈 본문은 JSON `null`과 다르게 취급합니다.
- 직접 등록한 Gson `JsonDeserializer`가 `JsonParseException`을 던져 입력을 거부해도 `400`입니다. 기본 생성자가 없는 클래스처럼 타입 자체의 문제(`JsonIOException`)는 `500`입니다.

```java
app.jsonLimit(256 * 1024);   // 256 KiB
```

Kotlin:

```kotlin
app.jsonLimit(256L * 1024)
app.jsonCodec(GsonJsonCodec(GsonJsonCodec.defaultGson(), 32))
```

[기본값과 보안](defaults.md)

## 다른 JSON 라이브러리 쓰기

`JsonCodec`을 구현해 `app.jsonCodec(...)`으로 넘깁니다. 공개 API에는 Gson 타입이 나오지 않으므로 앱 코드는 그대로입니다.

```java
public interface JsonCodec {
    <T> @Nullable T decode(Reader reader, Type type) throws IOException;   // null for a JSON null body
    void encode(@Nullable Object value, Writer writer) throws IOException;
}
```

| 라이브러리 | 이 프로젝트에서의 위치 | 사용 시 확인 |
|---|---|---|
| Gson 2.14.0 | 기본 구현 및 API 21 호환성 검사 기준 | 기본값 유지 |
| Moshi | 기본 제공 코덱 없음 | 버전별 Android 최소 API·Kotlin 생성자 처리 확인 |
| Jackson | 기본 제공 코덱 없음 | 버전별 JVM·Android 요구사항 확인 |
| kotlinx.serialization | 기본 제공 코덱 없음 | Kotlin 플러그인·직렬화 모델·Android 요구사항 확인 |

이 프로젝트의 API 21 기준을 유지하려면 고정된 Gson 2.14.0을 사용하세요. 코덱이나 라이브러리 버전을 바꾸면 해당 버전의 플랫폼 요구사항과 프로젝트의 호환성 검사를 다시 확인하세요.

## Kotlin 주의

Gson은 Kotlin `data class`의 기본 인자와 null 불가 타입을 이해하지 못합니다.

```kotlin
data class User(val name: String, val role: String = "user")
// No no-argument constructor: default Gson (Unsafe disabled) cannot instantiate this class.

data class Profile(val name: String = "", val role: String = "user")
// Defaults for every parameter generate a no-argument constructor, so decoding works.
// However, {"name":null} still puts null into the non-null name property.
```

Kotlin 앱이라면 Moshi(codegen)나 kotlinx.serialization 코덱을 쓰거나, DTO 필드를 nullable로 두고 검증하세요. [Kotlin](kotlin.md#json)
