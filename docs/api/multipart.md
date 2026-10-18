# 파일 업로드

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

```java
app.post("/api/upload", (req, res) -> {
    String title = req.form("title");
    UploadFile file = req.file("file");
    if (file == null) {
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
app.post("/api/upload") { req, res ->
    val file = req.file("file")
    if (file == null) {
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

| 설정 | 기본값 | 넘으면 |
|---|---|---|
| `maxFileSize` | 32 MiB | `413` |
| `maxRequestSize` | 64 MiB | `413` |
| `maxParts` | 20 | `413` |
| `memoryThreshold` | 64 KiB | 디스크 임시 파일로 전환 |
| `tempDirectory` | **(미확정)** | |

기본값은 Android 5.0 저사양 기기를 기준으로 작게 잡은 초안입니다. 큰 파일을 받으려면 명시적으로 올리세요. 무제한을 기본으로 허용하지 않습니다(무제한 설정 허용 여부는 **(미확정)**).
`Content-Length`가 없거나 chunked 전송이어도 받은 크기를 누적해서 검사합니다.

## UploadFile

```java
public interface UploadFile extends AutoCloseable {
    String name();          // form 필드 이름
    String filename();      // 클라이언트가 보낸 파일 이름 — 믿지 마세요
    String contentType();   // 클라이언트가 보낸 타입 — 믿지 마세요
    long size();
    InputStream inputStream() throws IOException;
    void save(File destination) throws IOException;
    void transferTo(OutputStream target) throws IOException;
    byte[] bytes() throws IOException;   // 작은 파일만
}
```

| 메서드 | 쓰는 곳 |
|---|---|
| `save(File)` | 파일로 저장. 성공하면 임시 파일은 정리됩니다. |
| `transferTo(OutputStream)` | Android SAF(`ContentResolver.openOutputStream(uri)`), 클라우드 업로드 등 다른 곳으로 복사 |
| `inputStream()` | 직접 읽기 |
| `bytes()` | 작은 파일을 메모리로. 한도를 넘으면 예외 |

`java.nio.file.Path`는 Android 5.0에 없어서 받지 않습니다. `File`과 스트림을 씁니다.

## 보안

- **`filename()`을 저장 경로에 그대로 쓰지 마세요.** `../../etc/passwd` 같은 값이 올 수 있습니다. 서버에서 이름을 만들고, 허용 확장자와 내용을 검사하고, 정해진 폴더 안에만 저장하세요.
- `contentType()`과 확장자는 클라이언트가 마음대로 정합니다. 파일 종류는 내용으로 확인하세요.

## 임시 파일 정리

- 업로드 파일은 `memoryThreshold`를 넘으면 임시 폴더에 저장됩니다. 요청 전체를 메모리에 올리지 않습니다.
- 임시 파일은 요청이 끝나거나 `save()`가 성공하면 지워집니다. 클라이언트가 중간에 끊어도 지워집니다.
- 그래서 **요청이 끝난 뒤에는 `UploadFile`을 쓸 수 없습니다.** `inputStream()`을 다른 스레드에 넘겨 나중에 읽지 마세요. 나중에 처리할 파일은 핸들러 안에서 `save()`하세요.
