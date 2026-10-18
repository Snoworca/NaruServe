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

로그 한 줄의 형식과 각 옵션의 기본값은 **(미확정)** 입니다.

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
app.logger().error("Upload failed", exception);
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

### 로거 교체

`Logger`를 구현해 `app.logger(customLogger)`로 바꿉니다.

| 환경 | 기본 로거 | 선택 |
|---|---|---|
| Android | Logcat | |
| JVM | 콘솔 | SLF4J 어댑터, 파일 로테이션 로거 |

`NaruServe-api`는 SLF4J와 `android.*`에 의존하지 않습니다. Logcat 로거, SLF4J 어댑터, 파일 로거를 담는 모듈 이름은 **(미확정)** 입니다.
파일 로거 예시 설정은 폴더 `./logs`, 파일당 5 MiB, 최대 5개입니다. 로그 쓰기가 실패해도 HTTP 서비스는 멈추지 않습니다.
