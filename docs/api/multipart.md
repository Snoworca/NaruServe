# 파일 업로드

[English](en/multipart.md) | [문서 목록](README.md)

Express에서 `multer`로 하던 일을 내장 기능으로 합니다. 미들웨어를 라우트마다 붙이지 않고, 앱 전체 한도만 설정한 뒤 `req.file(...)`로 꺼냅니다.

| multer | NaruServe |
|---|---|
| `upload.single('avatar')` + `req.file` | `req.file("avatar")` |
| `upload.array('photos')` + `req.files` | `req.files("photos")` |
| `upload.fields(...)` | 필드마다 `req.file(name)` / `req.files(name)` |
| `req.body.title` | `req.form("title")` |
| `limits: { fileSize }` | `app.multipart(c -> c.maxFileSize(...))` |
| `dest: 'uploads/'` | `c.tempDirectory(...)` (임시 폴더) + `file.save(...)` |

## 예제

서버 시작 전에 저장 폴더를 준비합니다. 전체 import와 main이 있는 [Java](../../examples/java/src/main/java/example/JavaExample.java)·[Kotlin](../../examples/kotlin/src/main/kotlin/example/KotlinExample.kt) 예제도 있습니다.

```java
File uploadDir = new File("./uploads");
if (!uploadDir.isDirectory() && !uploadDir.mkdirs() && !uploadDir.isDirectory()) {
    throw new IOException("Cannot create upload directory: " + uploadDir);
}

app.post("/api/upload", (req, res) -> {
    String title = req.form("title");
    UploadFile file = req.file("file");
    if (file == null || file.size() == 0) {  // 브라우저의 빈 파일 입력은 크기 0인 파일로 옵니다
        res.status(400).json("Missing file");
        return;
    }
    File dest = new File(uploadDir, UUID.randomUUID() + ".bin");  // 서버가 만든 이름
    file.save(dest);
    res.status(201).json(Collections.singletonMap("size", file.size()));
});
```

Kotlin:

```kotlin
val uploadDir = File("./uploads")
check(uploadDir.isDirectory || uploadDir.mkdirs()) { "Cannot create upload directory: $uploadDir" }

app.post("/api/upload") { req, res ->
    val file = req.file("file")
    if (file == null || file.size() == 0L) {  // 브라우저의 빈 파일 입력은 크기 0인 파일로 옵니다
        res.status(400).json("Missing file")
        return@post
    }
    file.save(File(uploadDir, "${UUID.randomUUID()}.bin"))
    res.status(201).json(mapOf("size" to file.size()))
}
```

## 한도 설정

```java
app.multipart(config -> {
    config.maxFileSize(100L * 1024 * 1024);    // 파일 하나 100 MiB
    config.maxRequestSize(200L * 1024 * 1024); // 요청 전체 200 MiB
    config.maxParts(50);                       // 파트 수
    config.memoryThreshold(64 * 1024);         // 이보다 크면 디스크로
    config.tempDirectory(new File("./temp"));
});
```

| 설정 | 기본값 | 허용 범위 | 넘으면 |
|---|---|---|---|
| `maxFileSize` | 32 MiB | 1 – 4294967296 (4 GiB) | `413` |
| `maxRequestSize` | 64 MiB | 1 – 4294967296 (4 GiB) | `413` |
| `maxParts` | 20 | 1 – 10000 | `413` |
| `memoryThreshold` | 64 KiB | 1 – 16777216 (16 MiB) | 디스크 임시 파일로 전환 |
| `tempDirectory` | `java.io.tmpdir` (Android에서는 앱 캐시 폴더) | `null`이면 `NullPointerException` | |

기본값은 Android 5.0 저사양 기기를 기준으로 작게 잡았습니다. 큰 파일을 받으려면 허용 범위 안에서 명시적으로 올리세요.
무제한 설정 허용 여부: 허용하지 않습니다. 범위는 양 끝을 포함하며, `0`, 음수, 상한보다 큰 값은 설정 이름을 담은 `IllegalArgumentException`입니다. 무제한을 막은 이유는 설정 실수 하나로 저사양 기기의 메모리와 저장 공간이 바닥나지 않게 하려는 것입니다.
`tempDirectory`의 기본값을 `java.io.tmpdir`로 정한 이유는 Android가 이 값을 앱 전용 캐시 폴더로 잡아 주어 따로 설정하지 않아도 쓸 수 있는 폴더이기 때문입니다.
`tempDirectory`가 없거나 쓸 수 없거나 디스크가 가득 차서 임시 파일을 만들지 못하면, 클라이언트의 잘못이 아니므로 `req.file(...)` 같은 접근자가 `HttpException(500)`을 던집니다. 본문이 깨진 경우는 그대로 `400`입니다.
`Content-Length`가 없거나 chunked 전송이어도 받은 크기를 누적해서 검사합니다.
설정은 `listen` 때 복사해 고정합니다. 설정 함수가 받은 `config`를 붙잡아 두었다가 `listen` 뒤에 바꿔도 실행 중인 앱의 한도는 바뀌지 않습니다.

### 텍스트 필드와 파트 헤더

- 텍스트 필드(`filename`이 없는 파트)는 디스크로 옮기지 않고 메모리에 둡니다. 한 요청의 텍스트 필드 내용 합계는 `app.formLimit(bytes)`(기본 1 MiB)로 제한하며, 넘으면 `413`입니다. multer의 `fieldSize`와 비슷하지만 필드 하나가 아니라 요청 전체 합계입니다. 이렇게 정한 이유는 텍스트 필드가 차지하는 메모리를 urlencoded 본문과 같은 한도로 묶으려는 것입니다.
- 텍스트 필드도 `maxRequestSize`와 `maxParts`에는 들어가지만 `maxFileSize`에는 들어가지 않습니다.
- 파트 헤더는 한 줄 8192바이트, 파트 하나의 헤더 합계 16384바이트로 고정되어 있고, 넘으면 `413`입니다. 이 두 값을 설정으로 열지 않은 이유는 정상 업로드의 파트 헤더가 이 한도에 가까이 가지 않기 때문입니다.

```java
app.formLimit(2L * 1024 * 1024);   // urlencoded 본문과 multipart 텍스트 필드 합계 2 MiB
```

## UploadFile

```java
import org.jspecify.annotations.Nullable;

public interface UploadFile extends AutoCloseable {
    String name();                     // form 필드 이름
    @Nullable String filename();       // 클라이언트가 보낸 파일 이름 — 믿지 마세요. 없으면 null
    @Nullable String contentType();    // 클라이언트가 보낸 타입 — 믿지 마세요. 없으면 null
    long size();
    InputStream inputStream() throws IOException;
    void save(File destination) throws IOException;
    void transferTo(OutputStream target) throws IOException;
    byte[] bytes() throws IOException; // 8 MiB까지만
    @Override
    void close();                      // checked 예외 없음
}
```

| 메서드 | 쓰는 곳 |
|---|---|
| `save(File)` | 파일로 저장. 성공하면 임시 파일은 정리됩니다. |
| `transferTo(OutputStream)` | Android SAF(`ContentResolver.openOutputStream(uri)`), 클라우드 업로드 등 다른 곳으로 복사 |
| `inputStream()` | 직접 읽기 |
| `bytes()` | 작은 파일을 메모리로. 8 MiB(8388608바이트)를 넘는 파트는 크기와 한도를 담은 `IllegalStateException` |

`bytes()`의 한도를 8 MiB로 고정한 이유는 큰 파일을 실수로 힙에 통째로 올리지 않게 하려는 것입니다. 더 큰 파일은 `inputStream()`, `transferTo`, `save`로 다루세요.
`transferTo(target)`는 대상 스트림을 flush하지만 닫지 않습니다. 대상 스트림은 호출자가 닫으세요. `save`·`close`·요청 종료 후에는 내용 접근이 막히지만 `name`·`filename`·`contentType`·`size` 메타데이터는 계속 읽을 수 있습니다.
`req.parts()`는 텍스트 필드까지 포함한 모든 파트를 본문 순서대로 `List<UploadFile>`로 돌려줍니다. 텍스트 필드는 `filename()`이 `null`입니다.

브라우저의 비어 있는 파일 입력은 `filename=""`인 파트를 보냅니다. `filename` 파라미터가 있는 파트는 모두 파일이므로, `req.file(name)`은 `null`이 아니라 `filename()`이 빈 문자열이고 `size()`가 0인 `UploadFile`을 돌려줍니다. 파일이 실제로 왔는지는 `file == null || file.size() == 0`으로 확인하세요.

`java.nio.file.Path`는 Android 5.0에 없어서 받지 않습니다. `File`과 스트림을 씁니다.

## 보안

- `filename()`은 클라이언트 값을 그대로 돌려줍니다. 브라우저처럼 역슬래시를 그대로 보낸 이름(`C:\fakepath\a.txt`)도 바뀌지 않고, 따옴표 안의 `\"`와 `\\`만 이스케이프로 풉니다.
- **`filename()`을 저장 경로에 그대로 쓰지 마세요.** `../../etc/passwd` 같은 값이 올 수 있습니다. 서버에서 이름을 만들고, 허용 확장자와 내용을 검사하고, 정해진 폴더 안에만 저장하세요.
- `contentType()`과 확장자는 클라이언트가 마음대로 정합니다. 파일 종류는 내용으로 확인하세요.

## 임시 파일 정리

- 업로드 파일은 `memoryThreshold`를 넘으면 임시 폴더에 저장됩니다. 요청 전체를 메모리에 올리지 않습니다.
- 임시 파일은 요청이 끝날 때, `save()`가 성공한 뒤, 한도를 넘어 `413`으로 답한 뒤, 클라이언트가 중간에 끊었을 때, 앱을 `shutdown`할 때 지워집니다.
- 요청은 응답이 끝나고 **그리고** 핸들러가 반환했을 때 끝납니다. 그래서 `res.status(202).send("queued")`로 먼저 답한 뒤 같은 핸들러 안에서 `file.save(dest)`해도 됩니다. 이렇게 정한 이유는 먼저 답하고 나중에 처리하는 Express/multer 패턴을 그대로 쓰게 하려는 것입니다.
- 그래서 **요청이 끝난 뒤에는 `UploadFile`을 쓸 수 없습니다.** `inputStream()`을 다른 스레드에 넘겨 나중에 읽지 마세요. 나중에 처리할 파일은 핸들러 안에서 `save()`하세요.
- `save()`나 `close()`는 그 파트의 `inputStream()`으로 받은 스트림을 먼저 닫고 임시 파일을 지웁니다. 열린 스트림이 Windows에서 삭제를 막지 않게 하려는 것입니다. 삭제가 실패한 임시 파일은 계속 추적해 `shutdown` 때 다시 지웁니다.
- 이미 쓸 수 없게 된 파트에 `save(dest)`를 부르면 `dest`를 건드리지 않고 `IllegalStateException`을 던집니다. 앞서 저장한 파일이 지워지지 않습니다.
