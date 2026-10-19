# 로깅

로그는 두 종류입니다.

| 종류 | 내용 | Express |
|---|---|---|
| 접근 로그 `AccessLog` | 요청마다 메서드, 경로, 상태, 걸린 시간, 바이트, requestId | `morgan` |
| 앱 로그 `Logger` | 앱과 프레임워크 이벤트 | `console.log` |

## 접근 로그

```java
app.accessLog(options -> {
    options.enabled(true);
    options.level(LogLevel.INFO);
    options.includeRequestId(true);
    options.includeDuration(true);
    options.includeBytes(true);
    options.exclude("/health");
    options.exclude("/favicon.ico");
});
```

| 옵션 | 설명 |
|---|---|
| `enabled(boolean)` | 켜기/끄기 |
| `level(LogLevel)` | 기록할 레벨 |
| `includeRequestId(boolean)` | requestId 포함 |
| `includeDuration(boolean)` | 처리 시간 포함 |
| `includeBytes(boolean)` | 주고받은 바이트 포함 |
| `exclude(path)` | 이 경로는 기록하지 않음 |

기본값: `enabled(true)`, `level(LogLevel.INFO)`, `includeRequestId(true)`, `includeDuration(true)`, `includeBytes(true)`, 제외 경로 없음. 기본을 모두 켠 이유는 저사양 기기에서도 문제를 추적할 정보가 처음부터 남게 하려는 것입니다.
`accessLog(...)`는 여러 번 불러도 앱의 옵션 하나에 순서대로 쌓이고, `listen()` 때 그 값으로 고정됩니다. `exclude(path)`는 쿼리를 뺀 원본 경로(`req.rawPath()`)와 대소문자까지 정확히 같을 때만 제외합니다.

### 한 줄 형식

요청 하나에 한 줄을 씁니다: `<method> <path> <status>[ aborted][ <n>ms][ in=<i> out=<o>][ rid=<id>]`. 대괄호 부분은 해당 옵션이 켜져 있을 때만 붙고(`aborted`는 응답이 중단됐을 때), 순서를 고정한 이유는 `grep`·`awk`로 바로 자르기 위해서입니다.

```text
GET /api/users/42 200 3ms in=0 out=27 rid=5f2c9a1e
POST /upload 413 aborted 12ms in=1048576 out=0 rid=5f2c9a1f
```

- `<path>`는 쿼리 문자열을 뺀 원본 경로입니다.
- `in`/`out`은 실제로 읽고 쓴 본문 바이트입니다(헤더 제외).
- `rid`는 요청 ID로, 같은 요청의 앱 로그·표준 오류 본문의 `requestId`와 같은 값입니다.

### 언제 기록하나

| 대상 | 기록 시점 |
|---|---|
| 일반 HTTP 요청 | 응답이 끝나거나 중단될 때 |
| WebSocket | Upgrade(`101`)할 때 한 줄, 연결이 끝날 때 close 코드·연결 시간을 한 줄 |
| SSE | 연결이 끝나거나 오류로 끊길 때 한 줄(시작부터 종료까지의 정보 포함) |

### 기록하지 않는 것

다음 값은 기본으로 접근 로그에 남기지 않습니다.

- `Authorization`, `Cookie` 헤더
- 비밀번호, 토큰
- 요청·응답 본문, 업로드 파일 내용
- 전체 query string

로그에 들어가는 값의 줄바꿈 등은 이스케이프해서 로그 위조를 막습니다.

## 앱 로거

```java
app.logger().info("Server initialized");
app.logger().error("Upload failed", new IOException("disk full"));
```

```java
public interface Logger {
    void trace(String m);
    void debug(String m);
    void info(String m);
    void warn(String m);
    void error(String m);
    void error(String m, Throwable t);
}
```

`onStart`/`onStop` 안에서는 `ctx.logger()`로도 같은 로거를 씁니다.

`RestApp.builder()`에서 `logger(...)`를 부르지 않으면 `ConsoleLogger`를 씁니다. 형식은 `<timestamp> <LEVEL> <message>`(timestamp는 `yyyy-MM-dd'T'HH:mm:ss.SSSZ`)이고, 기본 레벨은 `INFO`이며, `TRACE`·`DEBUG`·`INFO`는 `System.out`, `WARN`·`ERROR`는 `System.err`로 씁니다. `new ConsoleLogger(LogLevel.DEBUG)`처럼 레벨을 정할 수 있습니다.

같은 형식으로 쓰는 로거를 직접 만들 때는 `ConsoleLogger.format(level, message)`를 쓰세요. 줄 끝 없이 `<timestamp> <LEVEL> <message>` 한 줄을 돌려주고, 시간대는 부를 때마다 기본 시간대를 읽습니다. 파일 로테이션 로거도 이 메서드로 줄을 만들고 스택 트레이스를 뒤에 붙입니다. Express에는 대응 API가 없습니다(`morgan`의 사용자 형식 함수에 가깝습니다).

```java
String line = ConsoleLogger.format(LogLevel.WARN, "disk almost full");
```

```kotlin
val line = ConsoleLogger.format(LogLevel.WARN, "disk almost full")
```

### 로거 교체

`Logger`를 구현해 `app.logger(customLogger)`로 바꿉니다.

| 환경 | 기본 로거 | 선택 |
|---|---|---|
| Android | Logcat | |
| JVM | 콘솔 | SLF4J 어댑터, 파일 로테이션 로거 |

`NaruServe-api`는 SLF4J와 `android.*`에 의존하지 않습니다. 로거 구현은 각자 모듈에 있습니다.

| 로거 | 모듈 | 좌표 | Android |
|---|---|---|---|
| Logcat | `NaruServe-logcat` | `com.snoworca.naruserve:NaruServe-logcat:0.1.0-SNAPSHOT` | 지원(API 21+). Android SDK 없이 빌드하고 `android.util.Log`는 리플렉션으로 부릅니다 |
| 파일 로테이션 | `NaruServe-filelog` | `com.snoworca.naruserve:NaruServe-filelog:0.1.0-SNAPSHOT` | 지원(API 21+) |
| SLF4J 어댑터 | `NaruServe-slf4j` | `com.snoworca.naruserve:NaruServe-slf4j:0.1.0-SNAPSHOT` (`org.slf4j:slf4j-api:1.7.36`) | 미지원(JVM 전용) |

`RestApp.create()`용 `NaruServe` 모듈은 `NaruServe-logcat`을 실행 시점에만 함께 가져옵니다. 로거 클래스를 코드에서 직접 쓰려면 해당 모듈을 의존성에 넣으세요.
로그 쓰기가 실패해도 HTTP 서비스는 멈추지 않습니다.

### Logcat 로거

`new LogcatLogger()`는 태그 `NaruServe`로 씁니다. 다른 태그는 `new LogcatLogger("MyServer")`로 정합니다. Android가 아닌 JVM에서 만들면 `IllegalStateException`입니다.

### 파일 로거

`RotatingFileLogger.builder()`의 기본값은 폴더 `./logs`, 파일당 5 MiB(5242880 바이트), 최대 5개, 대기 큐 1024건, 레벨 `INFO`이며, 파일 이름은 `naruserve.log`이고 가득 차면 `naruserve.log.1`, `naruserve.log.2` … `naruserve.log.4`로 밀려나고 가장 오래된 파일은 지워집니다.
줄 형식은 콘솔 로거와 같은 `<timestamp> <LEVEL> <message>`입니다. 별도 스레드가 파일에 쓰며, 큐가 가득 차거나 쓰기가 실패하면 그 줄을 버리고 `droppedCount()`로 셉니다.
`naruserve.log`의 이름을 바꾸지 못해 회전이 실패하면 기존 줄을 지우지 않고 같은 파일에 이어 쓰며, 실패 횟수는 `rotationFailureCount()`로 셉니다. 다음 회전은 그 파일이 `maxFileBytes`만큼 더 커진 뒤에 다시 시도하고, 백업은 빈 번호가 없을 때만 가장 오래된 것을 지우므로 실패가 되풀이되어도 백업이 더 지워지지 않습니다. 실패가 계속되는 동안 `naruserve.log`는 `maxFileBytes`보다 커질 수 있으니 `rotationFailureCount()`가 0이 아니면 폴더 권한과 디스크를 확인하세요.

Android에서는 작업 폴더 `./logs`에 쓸 수 없으므로 `context.getFilesDir()` 같은 쓰기 가능한 폴더를 넘기세요. 기본 폴더를 Android용으로 바꾸지 않은 이유는 `NaruServe-filelog`가 `android.*`에 의존하지 않기 때문입니다.

```java
RotatingFileLogger logger = RotatingFileLogger.builder()
    .directory(new File(context.getFilesDir(), "logs"))
    .build();
```

### 종료와 로거

`shutdown(...)`과 시작 실패 정리는 끝 무렵(HTTP 어댑터를 놓기 직전)에 앱 로거를 `flush()`(`Flushable`일 때)한 뒤 `close()`(`AutoCloseable`일 때)하며, 그렇게 하는 이유는 파일 로거의 큐에 남은 줄을 잃지 않기 위해서입니다. `ConsoleLogger`의 `close()`는 `System.out`/`System.err`를 flush만 하고 닫지 않습니다.
여러 앱이 로거 하나를 같이 쓰면 먼저 종료한 앱이 그 로거를 닫으므로, 앱마다 `close()`가 아무것도 하지 않는(`AutoCloseable`을 구현하지 않은) 위임 로거를 넘기세요.
