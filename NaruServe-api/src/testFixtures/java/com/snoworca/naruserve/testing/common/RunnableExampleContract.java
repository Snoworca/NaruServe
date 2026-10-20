package com.snoworca.naruserve.testing.common;

import com.snoworca.naruserve.RestApp;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** Real HTTP contract shared by the Java and Kotlin source examples. @req OPS-DX-001 */
public final class RunnableExampleContract {
    public interface Factory { RestApp create(File uploads) throws Exception; }
    private RunnableExampleContract() {}

    public static void verify(Factory factory) throws Exception {
        File root = Files.createTempDirectory("naruserve-example-").toFile();
        File uploads = new File(root, "new/uploads");
        RestApp app = null;
        try {
            app = factory.create(uploads);
            check(uploads.isDirectory(), "example must prepare upload directory at startup");
            int port = app.listen(0).port();
            request(port, "GET", "/hello", null, null, 200, "Hello World");
            request(port, "GET", "/page", null, null, 200, "1");
            request(port, "GET", "/page?page=abc", null, null, 400, "BAD_REQUEST");
            request(port, "POST", "/users", "application/json", "{}", 400, "name is required");
            request(port, "POST", "/users", "application/json", "null", 400, "BAD_REQUEST");
            request(port, "POST", "/users", "application/json", "{\"name\":null}", 400, "name is required");
            request(port, "POST", "/users", "application/json", "{\"name\":\"Ada\"}", 201, "Ada");
            request(port, "GET", "/users/1", null, null, 200, "Ada");
            request(port, "POST", "/upload", "multipart/form-data; boundary=sample", "--sample--\r\n", 400, "Missing file");
            String multipart = "--sample\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.txt\"\r\n"
                    + "Content-Type: text/plain\r\n\r\npersisted\r\n--sample--\r\n";
            request(port, "POST", "/upload", "multipart/form-data; boundary=sample", multipart, 201, "9");
            File[] files = uploads.listFiles();
            check(files != null && files.length == 1, "one upload must be persisted");
            check("persisted".equals(new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8)), "upload bytes");
            request(port, "GET", "/stream", null, null, 200, "stream example");
        } finally {
            if (app != null) app.shutdown(0, TimeUnit.SECONDS);
            delete(root);
        }
    }

    private static void request(int port, String method, String path, String contentType, String body,
            int expectedStatus, String expectedText) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Connection", "close");
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                if (input != null) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) result.write(buffer, 0, count);
                }
            }
            String text = new String(result.toByteArray(), StandardCharsets.UTF_8);
            check(status == expectedStatus, method + " " + path + " status " + status + ": " + text);
            check(text.contains(expectedText), method + " " + path + " body: " + text);
        } finally {
            connection.disconnect();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
