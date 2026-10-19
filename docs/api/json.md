# JSON

Express의 `express.json()`과 `res.json()`에 해당합니다. NaruServe는 JSON 처리를 `JsonCodec` 인터페이스 뒤에 두고, 기본 구현으로 **Gson 2.14.0**을 씁니다.

## 읽기와 쓰기

```java
User user = req.body(User.class);                          // 요청 → 객체
List<User> users = req.body(new TypeRef<List<User>>() {});  // 제네릭
res.json(users);                                            // 객체 → 응답
ws.sendJson(user);                                          // WebSocket
```

- `req.body(...)`는 `Content-Type`이 JSON일 때만 읽습니다. 본문이 JSON `null`이면 `null`을 돌려줍니다. 오류와 한도는 [Request](request.md#json).
- `TypeRef<T>`는 `List<User>` 같은 제네릭 타입 정보를 보존합니다. 끝의 `{}`(익명 클래스)를 빼먹지 마세요.
- Kotlin의 `object : TypeRef<List<String>>() {}`는 Java의 `new TypeRef<List<String>>() {}`와 같은 `List<String>`을 담습니다. Kotlin은 선언 위치 변성 때문에 `List<? extends String>`을 기록하지만, `TypeRef`가 Kotlin 클래스(`kotlin.Metadata`가 남아 있을 때)에서는 `? extends` 와일드카드를 떼어 냅니다. R8이 이 어노테이션을 지운 빌드에서는 와일드카드가 남지만 기본 Gson 코덱은 두 타입을 똑같이 읽습니다.
- `res.json`은 객체, 배열, `Map`, 문자열, 숫자, `null`을 모두 올바른 JSON으로 씁니다.

## DTO 작성

```java
public class User {
    @SerializedName("id")   private String id;
    @SerializedName("name") private String name;

    public User() {}   // 기본 생성자
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

`GsonJsonCodec`은 `NaruServe-api`에 들어 있지 않습니다(공개 API는 Gson에 의존하지 않습니다). `GsonJsonCodec`은 `NaruServe-gson` 모듈에 있습니다. 이 모듈은 Gson을 `api`로 노출하므로 `Gson`을 직접 만들 때 따로 의존성을 넣지 않아도 됩니다. `RestApp.create()`용 bootstrap 모듈 `com.snoworca.naruserve:NaruServe`는 이 모듈을 실행 시점에만 함께 가져옵니다. 위처럼 `Gson`과 `GsonJsonCodec`을 코드에서 직접 쓰려면 아래 의존성을 따로 넣으세요.

```groovy
implementation 'com.snoworca.naruserve:NaruServe-gson:0.1.0-SNAPSHOT'
// 함께 들어오는 Gson: com.google.code.gson:gson:2.14.0
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
    <T> @Nullable T decode(Reader reader, Type type) throws IOException;   // JSON null이면 null
    void encode(@Nullable Object value, Writer writer) throws IOException;
}
```

| 라이브러리 | Android 5.0 (API 21) | 권장 |
|---|---|---|
| Gson 2.14.0 | 공식 지원 | 기본값 |
| Moshi 1.15.x | 버전별 확인 필요 | Kotlin 앱에 유리 |
| Jackson 2.21.x | 미지원(공식 API 26+) | JVM 전용 앱에서만 |
| kotlinx.serialization | Kotlin 전용 | Kotlin 선택 확장 |

**Gson 버전을 올리지 마세요.** Gson 2.15부터는 공식 지원이 Android API 24 이상입니다.

## Kotlin 주의

Gson은 Kotlin `data class`의 기본 인자와 null 불가 타입을 이해하지 못합니다.

```kotlin
data class User(val name: String, val role: String = "user")
// 인자 없는 생성자가 없습니다. 기본 설정(Unsafe 끔)의 Gson은 이 클래스를 만들지 못하고 예외가 납니다.

data class Profile(val name: String = "", val role: String = "user")
// 모든 인자에 기본값이 있으면 인자 없는 생성자가 생겨 읽을 수 있습니다.
// 하지만 {"name":null} 을 받으면 null 불가 타입인 name에 null이 들어갑니다.
```

Kotlin 앱이라면 Moshi(codegen)나 kotlinx.serialization 코덱을 쓰거나, DTO 필드를 nullable로 두고 검증하세요. [Kotlin](kotlin.md#json)
