# 실행 가능한 Java·Kotlin 예제

[English](README.md) | 한국어

두 앱은 기본 Gson 코덱과 TinyHttpd를 사용합니다. 전체 import와 실행 진입점, 스레드 안전한 메모리 사용자 서비스, nullable 요청 DTO 검증, 파일 업로드, 프레임워크가 소유하는 스트림 응답을 포함합니다. 프로세스가 종료되면 데이터가 사라지는 학습용 예제입니다.

## 소스 체크아웃에서 실행

JDK 17과 이 체크아웃의 Gradle 8.14.3 wrapper를 사용하세요. 라이브러리 바이트코드는 Java 8을 대상으로 합니다. NaruServe 루트에서 배포된 TinyHttpd JAR를 사용하는 예제 하나를 실행합니다.

```bash
./gradlew -p examples/java -PtinyHttpdFromMaven=true run
# 또는 Java를 종료하고 같은 포트에서 Kotlin을 실행합니다.
./gradlew -p examples/kotlin -PtinyHttpdFromMaven=true run
```

각 예제의 `settings.gradle`은 `includeBuild('../..')`로 NaruServe 루트를 포함합니다. Gradle이 Maven 좌표를 소스 모듈로 치환하며, 엔진 선택 속성도 포함된 소스 빌드에 전달합니다. 이 소스 실행 경로에는 로컬 게시가 필요 없습니다. TinyHttpd 소스를 사용하려면 `-PtinyHttpdFromMaven=true` 대신 `-PtinyHttpdDir=/absolute/path/to/TinyHttpd`를 지정하세요.

기본 포트는 3000, 바인딩 주소는 127.0.0.1입니다. 포트와 업로드 디렉터리는 다음과 같이 바꿉니다.

```bash
./gradlew -p examples/java -PtinyHttpdFromMaven=true run --args='3001 /tmp/naruserve-uploads'
```

앱은 시작할 때 업로드 디렉터리를 만들고, 만들 수 없으면 즉시 실패합니다. 절대 경로를 쓰지 않으면 앱 작업 디렉터리를 기준으로 해석합니다. 클라이언트 파일 이름을 저장 경로로 사용하지 마세요.

## 라우트 사용해 보기

```bash
curl http://127.0.0.1:3000/hello
curl 'http://127.0.0.1:3000/page?page=2'
curl -i -H 'Content-Type: application/json' -d '{"name":"Ada"}' http://127.0.0.1:3000/users
curl http://127.0.0.1:3000/users/1
curl -i -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:3000/users
curl -i -F note=missing-file http://127.0.0.1:3000/upload
curl -i -F file=@README.md http://127.0.0.1:3000/upload
curl http://127.0.0.1:3000/stream
```

`GET /hello`는 `Hello World`를 반환합니다. 사용자를 만들면 201을 반환하며, 후속 요청에는 반환된 ID를 사용하세요. 이름이 없거나 null·공백이면 400으로 응답합니다. JSON `null`도 400입니다. `page`는 누락된 경우에만 1을 기본값으로 사용하며, 숫자 형식이 잘못되면 400입니다. 업로드가 없거나 비어 있으면 400이고, 유효한 업로드는 요청이 끝나기 전에 서버가 만든 이름으로 저장합니다. NaruServe는 응답 스트림의 소유권을 가져와 전송 후 닫습니다. 호출 지점에서 Java try-with-resources나 Kotlin `use`로 감싸지 마세요.

Kotlin 요청 DTO는 Unsafe를 사용하지 않는 기본 Gson에 맞춰 nullable 프로퍼티와 인자 없는 생성자를 사용합니다. 인자 없는 생성자가 없는 불변 data class에는 다른 코덱을 명시적으로 설정해야 합니다. Unsafe를 켜면 생성자를 우회하므로 해결책으로 삼지 마세요.

## 별도 소비자에서 Maven 좌표 사용

별도 소비자 프로젝트에 예제 소스와 빌드 파일을 복사하고, `settings.gradle`에서 `includeBuild('../..')`를 제거하세요. `mavenCentral()`을 유지하고, 해당 버전을 Maven Central에서 받을 수 있을 때 Java는 `com.snoworca.naruserve:naruserve:0.9.0`, Kotlin은 `com.snoworca.naruserve:naruserve-kotlin:0.9.0`을 사용하세요. 가용성과 모듈 선택은 [설치 안내](../README.ko.md#설치)에서 확인하세요.

버전을 찾지 못하면 NaruServe 루트에서 이 체크아웃을 로컬에 게시합니다.

```bash
./gradlew -PtinyHttpdFromMaven=true publishToMavenLocal
```

그런 다음 별도 소비자의 `mavenCentral()` 앞에 `mavenLocal()`을 추가하세요. NaruServe는 아홉 JAR 모듈과, Android SDK가 있으면 Android AAR를 게시합니다. TinyHttpd 자체는 게시하지 않습니다. TinyHttpd 3.1.1 core·websocket 의존성은 소스 체크아웃 없이 Maven Central에서 가져옵니다. 검증한 TinyHttpd 커밋과 제약은 [루트 README](../README.ko.md#tinyhttpd)를 참고하세요.

## 문제 해결

| 증상 | 원인과 조치 |
|---|---|
| `tinyHttpdDir: TinyHttpd checkout not found` | `-PtinyHttpdFromMaven=true`로 바이너리 의존성을 선택하거나 유효한 TinyHttpd 소스 디렉터리를 넘기세요. |
| 서버가 3000 포트에 바인딩할 수 없음 | 다른 예제를 종료하거나 위의 `--args` 방식으로 다른 포트를 넘기세요. |
| 다른 기기에서 서버에 연결할 수 없음 | 예제는 루프백에서만 받습니다. LAN에서 시험하기 전에 앱의 서버 설정을 명시적으로 바꾸세요. |
| 업로드 디렉터리를 만들 수 없음 | 앱의 두 번째 인자로 쓸 수 있는 절대 디렉터리 경로를 넘기세요. |

응답·업로드·수명주기 계약은 [API 가이드](../docs/api/README.md)를 참고하세요.
