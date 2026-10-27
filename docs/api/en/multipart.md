# File uploads

[한국어](../multipart.md) | [API index](README.md)

The built-in multipart reader replaces typical multer usage. Configure app-wide limits, then read files with req.file; there is no per-route upload middleware requirement.

| multer | NaruServe |
|---|---|
| upload.single('avatar') + req.file | req.file("avatar") |
| upload.array('photos') + req.files | req.files("photos") |
| upload.fields(...) | file/files accessors for each name |
| req.body.title | req.form("title") |
| limits.fileSize | app.multipart(c -> c.maxFileSize(...)) |
| dest: uploads | tempDirectory for temporary storage, then file.save for persistence |

## Example

Prepare the destination directory before starting. See complete [Java](../../../examples/java/src/main/java/example/JavaExample.java) and [Kotlin](../../../examples/kotlin/src/main/kotlin/example/KotlinExample.kt) programs for imports and entry points. The following snippets run inside an app configuration.

```java
File uploadDir = new File("./uploads");
if (!uploadDir.isDirectory() && !uploadDir.mkdirs() && !uploadDir.isDirectory()) {
    throw new IOException("Cannot create upload directory: " + uploadDir);
}

app.post("/api/upload", (req, res) -> {
    String title = req.form("title");
    UploadFile file = req.file("file");
    if (file == null || file.size() == 0) {  // an empty browser file input produces a zero-byte file
        res.status(400).json("Missing file");
        return;
    }
    File dest = new File(uploadDir, UUID.randomUUID() + ".bin");  // server-generated filename
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
    if (file == null || file.size() == 0L) {  // an empty browser file input produces a zero-byte file
        res.status(400).json("Missing file")
        return@post
    }
    file.save(File(uploadDir, "${UUID.randomUUID()}.bin"))
    res.status(201).json(mapOf("size" to file.size()))
}
```

## Configure limits

```java
app.multipart(config -> {
    config.maxFileSize(100L * 1024 * 1024);    // 100 MiB per file
    config.maxRequestSize(200L * 1024 * 1024); // 200 MiB for the whole request
    config.maxParts(50);                       // part count
    config.memoryThreshold(64 * 1024);         // spill larger files to disk
    config.tempDirectory(new File("./temp"));
});
```

| Option | Default | Inclusive range | Exceeded |
|---|---|---|---|
| maxFileSize | 32 MiB | 1..4294967296 bytes (4 GiB) | 413 |
| maxRequestSize | 64 MiB | 1..4294967296 bytes (4 GiB) | 413 |
| maxParts | 20 | 1..10000 | 413 |
| memoryThreshold | 64 KiB | 1..16777216 bytes (16 MiB) | Spill file content to disk |
| tempDirectory | java.io.tmpdir, app cache directory on Android | null throws NullPointerException | Storage failure gives 500 |

Small defaults target low-memory Android 5.0 use; this is not a claim of device testing. Increase them explicitly within the ranges. Unlimited values are unsupported: zero, negatives or values above the ceiling throw IllegalArgumentException naming the setting, preventing accidental unbounded storage/memory use.

java.io.tmpdir uses app-private cache storage on Android. If the directory is missing/unwritable or the disk is full, accessors such as req.file throw HttpException(500), since it is a server storage failure. Malformed multipart still gives 400. Limits count received bytes even without Content-Length or with chunked transfer.

Options are copied and frozen at listen. Retaining and changing the config object afterward does not change a running server.

### Text fields and part headers

- Text parts without filename remain in memory. app.formLimit, default 1 MiB, limits their aggregate content for one request; exceeding it gives 413. Unlike multer fieldSize, it is an aggregate rather than per-field bound, sharing the urlencoded memory budget.
- Text parts count toward maxRequestSize and maxParts, but not maxFileSize.
- Part headers have fixed limits of 8192 bytes per line and 16384 bytes per part. Exceeding either gives 413. Normal upload headers do not need configurable larger limits.

```java
app.formLimit(2L * 1024 * 1024);   // 2 MiB for urlencoded bodies and aggregate multipart text fields
```

## UploadFile

```java
import org.jspecify.annotations.Nullable;

public interface UploadFile extends AutoCloseable {
    String name();                     // form field name
    @Nullable String filename();       // untrusted client filename; null if absent
    @Nullable String contentType();    // untrusted client type; null if absent
    long size();
    InputStream inputStream() throws IOException;
    void save(File destination) throws IOException;
    void transferTo(OutputStream target) throws IOException;
    byte[] bytes() throws IOException; // at most 8 MiB
    @Override
    void close();                      // no checked exception
}
```

| Method | Use |
|---|---|
| save(File) | Persist to a destination; success releases temporary content |
| transferTo(OutputStream) | Copy to Android SAF, cloud storage or another output |
| inputStream() | Read content yourself |
| bytes() | Read at most 8 MiB / 8388608 bytes; larger parts throw IllegalStateException naming size and limit |

The fixed bytes limit prevents accidentally loading a large file into the heap; use streams, transferTo or save for larger parts. transferTo flushes but does not close the target: the caller owns it. After save/close/request completion, content access is forbidden, but name, filename, contentType and size metadata remains readable.

req.parts returns every part in body order, including text parts whose filename is null. An empty browser file input sends filename="": it is still a file part, represented by a non-null UploadFile with empty filename and size zero. Check for missing or zero-size files, or use req.requireFile(name).

The API uses File and streams rather than java.nio.file.Path, which is unavailable on Android 5.0.

## Security

- filename preserves client input. Backslashes in names such as C:\fakepath\a.txt remain; only escaped quote/backslash sequences inside quoted header values are unescaped.
- Never directly use filename as a destination path: it can contain traversal such as ../../etc/passwd. Generate a server-side name, validate permitted types and content, and save only within a fixed directory.
- contentType and extensions are client-controlled. Inspect contents to identify file types.

## Temporary-file cleanup

- Files larger than memoryThreshold spool to temporary storage rather than buffering the whole request.
- Cleanup runs after request completion, successful save, a size-limit rejection, disconnect and app shutdown.
- A request completes only when both the response is finished and the handler returns. You may send an early 202 and then save within that same handler, supporting the familiar Express/multer pattern.
- Do not use upload content after the request ends or pass inputStream to another thread for later processing. Save persistent content inside the handler first.
- save and close first close streams obtained from that part, then delete its temporary file, so Windows open handles do not block deletion. Failed deletions remain tracked for a shutdown retry.
- Calling save on a released part throws IllegalStateException before touching the destination, preserving an already saved file.
