package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/** req.form, file, files and parts over the in-memory adapter, through the public API only. */
public class FormAccessorsTest {

    private static final String B = "XyZ";
    private static final String MP = "multipart/form-data; boundary=" + B;
    private static final String URL = "application/x-www-form-urlencoded";

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final List<Object> seen = new ArrayList<>();
    private @Nullable RestApp app;

    @After
    public void stop() {
        RestApp a = app;
        if (a != null && a.state() == AppState.RUNNING) {
            a.shutdown(5, TimeUnit.SECONDS);
        }
    }

    private RestApp app() {
        RestApp a = RestApp.builder().httpAdapter(adapter).jsonCodec(new JsonBodyAcceptanceTest.TextCodec()).build();
        app = a;
        return a;
    }

    private ClientResponse post(@Nullable String contentType, byte[] body) {
        RestApp a = app;
        if (a != null && a.state() != AppState.RUNNING) {
            a.listen(0);
        }
        ClientRequest r = ClientRequest.of("POST", "/f").body(body);
        if (contentType != null) {
            r.header("Content-Type", contentType);
        }
        r.header("Content-Length", String.valueOf(body.length));
        return adapter.send(r);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** One part: {@code filename} null makes a text field. */
    private static String part(String name, @Nullable String filename, String content) {
        return "--" + B + "\r\nContent-Disposition: form-data; name=\"" + name + "\""
                + (filename == null ? "" : "; filename=\"" + filename + "\"") + "\r\n"
                + (filename == null ? "" : "Content-Type: text/plain\r\n") + "\r\n" + content + "\r\n";
    }

    private static byte[] multipart(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(p);
        }
        return utf8(sb.append("--").append(B).append("--\r\n").toString());
    }

    private static String text(UploadFile f) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = f.inputStream()) {
            byte[] b = new byte[256];
            int n;
            while ((n = in.read(b)) >= 0) {
                out.write(b, 0, n);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String name(Throwable t) {
        return t.getClass().getSimpleName();
    }

    // SDS-AC-1; FR-MULTIPART-003 AC-2: a JSON body is not read by the four accessors and body(...) still reads it
    @Test
    public void sdsAc1_FR_MULTIPART_003_AC2_jsonBodyStaysUnreadAndBodyStillReadsIt() {
        app().post("/f", (req, res) -> {
            seen.add(String.valueOf(req.form("a")));
            seen.add(String.valueOf(req.file("a")));
            seen.add(req.files("a").size());
            seen.add(req.parts().size());
            seen.add(req.body(String.class));
            res.send("ok");
        });
        assertEquals(200, post("application/json", utf8("\"hi\"")).status());
        assertEquals(Arrays.<Object>asList("null", "null", 0, 0, "\"hi\""), seen);
    }

    // SDS-AC-1; FR-MULTIPART-003 AC-2: once body(...) read the body, every accessor throws BodyAlreadyConsumed
    @Test
    public void sdsAc1_FR_MULTIPART_003_AC2_afterBodyEveryAccessorThrowsBodyAlreadyConsumed() {
        app().post("/f", (req, res) -> {
            req.body(String.class);
            for (int i = 0; i < 4; i++) {
                try {
                    if (i == 0) {
                        req.form("a");
                    } else if (i == 1) {
                        req.file("a");
                    } else if (i == 2) {
                        req.files("a");
                    } else {
                        req.parts();
                    }
                    seen.add("no throw " + i);
                } catch (BodyAlreadyConsumedException e) {
                    seen.add(name(e));
                }
            }
            res.send("ok");
        });
        assertEquals(200, post("application/json", utf8("\"hi\"")).status());
        assertEquals(Arrays.<Object>asList("BodyAlreadyConsumedException", "BodyAlreadyConsumedException",
                "BodyAlreadyConsumedException", "BodyAlreadyConsumedException"), seen);
    }

    // SDS-AC-1; FR-MULTIPART-003 AC-2: no Content-Type at all is a non-form body
    @Test
    public void sdsAc1_FR_MULTIPART_003_AC2_missingContentTypeIsNotAForm() {
        app().post("/f", (req, res) -> {
            seen.add(String.valueOf(req.form("a")));
            seen.add(req.parts().size());
            res.send("ok");
        });
        assertEquals(200, post(null, utf8("a=1")).status());
        assertEquals(Arrays.<Object>asList("null", 0), seen);
    }

    // SDS-AC-1; FR-MULTIPART-003 AC-2: the media type before ';' is trimmed and matched case-insensitively
    @Test
    public void sdsAc1_FR_MULTIPART_003_AC2_mediaTypeIsCaseInsensitiveAndIgnoresParameters() {
        app().post("/f", (req, res) -> {
            seen.add(req.form("a"));
            res.send("ok");
        });
        assertEquals(200, post(" Application/X-WWW-Form-URLEncoded ; charset=UTF-8", utf8("a=1")).status());
        assertEquals(200, post("MULTIPART/Form-Data; boundary=" + B, multipart(part("a", null, "2"))).status());
        assertEquals(Arrays.<Object>asList("1", "2"), seen);
    }

    // SDS-AC-2; FR-MULTIPART-003 AC-1: a pair without '=' is "", an empty pair is skipped
    @Test
    public void sdsAc2_FR_MULTIPART_003_AC1_pairWithoutEqualsIsEmptyAndEmptyPairIsSkipped() {
        app().post("/f", (req, res) -> {
            seen.add(req.form("a"));
            seen.add(req.form("b"));
            seen.add(String.valueOf(req.form("")));
            res.send("ok");
        });
        assertEquals(200, post(URL, utf8("a&&b=1")).status());
        assertEquals(Arrays.<Object>asList("", "1", "null"), seen);
    }

    // SDS-AC-2; FR-MULTIPART-003 AC-1: a multipart text part with malformed UTF-8 answers 400 with the standard body
    @Test
    public void sdsAc2_FR_MULTIPART_003_AC1_multipartTextPartWithMalformedUtf8Gives400() {
        app().post("/f", (req, res) -> {
            seen.add("handler");
            req.form("a");
            res.send("unreachable");
        });
        byte[] head = utf8("--" + B + "\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n");
        byte[] tail = utf8("\r\n--" + B + "--\r\n");
        byte[] body = new byte[head.length + 2 + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        body[head.length] = (byte) 0xC3;
        body[head.length + 1] = (byte) 0x28;
        System.arraycopy(tail, 0, body, head.length + 2, tail.length);
        ClientResponse r = post(MP, body);
        assertEquals(400, r.status());
        assertTrue(new String(r.body(), StandardCharsets.UTF_8).contains("\"code\":\"BAD_REQUEST\""));
        assertEquals(Arrays.<Object>asList("handler"), seen);
    }

    // FR-MULTIPART-003 AC-1: urlencoded decoding by the form rules; malformed encoding answers 400 through onError
    @Test
    public void FR_MULTIPART_003_AC1_urlencodedValuesDecodeAndMalformedEncodingGives400ThroughOnError() {
        RestApp a = app();
        a.onError((error, req, res) -> seen.add("onError " + ((HttpException) error).status()));
        a.post("/f", (req, res) -> {
            seen.add(req.form("name"));
            seen.add(req.form("city"));
            seen.add(String.valueOf(req.form("zip")));
            res.send("ok");
        });
        assertEquals(200, post(URL, utf8("name=Kim+Lee&name=x&city=%EC%84%9C%EC%9A%B8")).status());
        assertEquals(Arrays.<Object>asList("Kim Lee", "서울", "null"), seen);
        for (String bad : new String[] {"a=%G1", "a=%4", "a=%FF"}) {
            seen.clear();
            ClientResponse r = post(URL, utf8(bad));
            assertEquals(bad, 400, r.status());
            assertTrue(bad, new String(r.body(), StandardCharsets.UTF_8).contains("\"code\":\"BAD_REQUEST\""));
            assertEquals(bad, Arrays.<Object>asList("onError 400"), seen);
        }
    }

    // SDS-AC-3; FR-MULTIPART-003 AC-1; IR-MULTIPART-002 AC-3: urlencoded has no parts and no files
    @Test
    public void sdsAc3_FR_MULTIPART_003_AC1_urlencodedHasEmptyPartsAndNoFiles() {
        app().post("/f", (req, res) -> {
            seen.add(req.form("doc"));
            seen.add(String.valueOf(req.file("doc")));
            seen.add(req.files("doc").size());
            seen.add(req.parts().size());
            res.send("ok");
        });
        assertEquals(200, post(URL, utf8("doc=a&doc=b")).status());
        assertEquals(Arrays.<Object>asList("a", "null", 0, 0), seen);
    }

    // FR-MULTIPART-003 AC-1, AC-3, AC-4; IR-MULTIPART-002 AC-3: text fields, files and parts in body order
    @Test
    public void FR_MULTIPART_003_AC3_AC4_mixedMultipartExposesFieldsFilesAndPartsInBodyOrder() {
        app().post("/f", (req, res) -> {
            seen.add(req.form("title"));
            seen.add(req.form("tag"));
            seen.add(String.valueOf(req.form("doc"))); // a file part is never a form value
            UploadFile doc = req.file("doc");
            seen.add(doc == null ? "no doc" : doc.filename() + ":" + text(doc));
            seen.add(String.valueOf(req.file("title"))); // a text field is never a file
            for (UploadFile p : req.files("photos")) {
                seen.add(p.filename() + ":" + text(p));
            }
            List<String> order = new ArrayList<>();
            for (UploadFile p : req.parts()) {
                order.add(p.name() + "/" + p.filename());
            }
            seen.add(order);
            seen.add(req.files("none").size());
            res.send("ok");
        });
        byte[] body = multipart(part("title", null, "Hello"), part("photos", "a.png", "A"), part("tag", null, "t1"),
                part("doc", "d.txt", "D"), part("photos", "b.png", "B"));
        assertEquals(200, post(MP, body).status());
        assertEquals(Arrays.<Object>asList("Hello", "t1", "null", "d.txt:D", "null", "a.png:A", "b.png:B",
                Arrays.asList("title/null", "photos/a.png", "tag/null", "doc/d.txt", "photos/b.png"), 0), seen);
    }

    // FR-MULTIPART-003 AC-5: repeated calls share one parse and return the same instances; body(...) then throws
    @Test
    public void FR_MULTIPART_003_AC5_repeatedCallsReturnTheSameInstancesAndBodyThenThrows() {
        app().post("/f", (req, res) -> {
            UploadFile f = req.file("doc");
            seen.add(f != null && f == req.file("doc") && f == req.files("doc").get(0) && f == req.parts().get(1));
            seen.add(req.parts() == req.parts());
            try {
                req.body(String.class);
                seen.add("no throw");
            } catch (BodyAlreadyConsumedException e) {
                seen.add(name(e));
            }
            res.send("ok");
        });
        assertEquals(200, post(MP, multipart(part("t", null, "x"), part("doc", "d.txt", "D"))).status());
        assertEquals(Arrays.<Object>asList(true, true, "BodyAlreadyConsumedException"), seen);
    }

    // SDS-AC-5; FR-MULTIPART-003 AC-5: after a 413 parse failure a second form() throws BodyAlreadyConsumed
    @Test
    public void sdsAc5_FR_MULTIPART_003_AC5_secondFormAfter413ThrowsBodyAlreadyConsumed() {
        app().formLimit(4).post("/f", (req, res) -> {
            try {
                req.form("a");
                seen.add("no throw");
            } catch (HttpException e) {
                seen.add(e.status());
            }
            try {
                req.form("a");
                seen.add("no throw");
            } catch (BodyAlreadyConsumedException e) {
                seen.add(name(e));
            }
            res.send("ok");
        });
        assertEquals(200, post(URL, utf8("a=12345")).status());
        assertEquals(Arrays.<Object>asList(413, "BodyAlreadyConsumedException"), seen);
    }

    // SDS-AC-5; FR-MULTIPART-003 AC-5: after a 400 multipart failure (no boundary) later accessors throw too
    @Test
    public void sdsAc5_FR_MULTIPART_003_AC5_partsAfterA400ParseFailureThrowsBodyAlreadyConsumed() {
        app().post("/f", (req, res) -> {
            try {
                req.parts();
                seen.add("no throw");
            } catch (HttpException e) {
                seen.add(e.status());
            }
            try {
                req.file("a");
                seen.add("no throw");
            } catch (BodyAlreadyConsumedException e) {
                seen.add(name(e));
            }
            res.send("ok");
        });
        assertEquals(200, post("multipart/form-data", multipart(part("a", null, "1"))).status());
        assertEquals(Arrays.<Object>asList(400, "BodyAlreadyConsumedException"), seen);
    }

    // IR-MULTIPART-002 AC-1: the four Request accessors with exactly these signatures
    @Test
    public void IR_MULTIPART_002_AC1_requestDeclaresTheFourAccessors() throws Exception {
        Method form = Request.class.getMethod("form", String.class);
        assertSame(String.class, form.getReturnType());
        assertTrue(form.getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        Method file = Request.class.getMethod("file", String.class);
        assertSame(UploadFile.class, file.getReturnType());
        assertTrue(file.getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        Method files = Request.class.getMethod("files", String.class);
        assertEquals("java.util.List<com.snoworca.naruserve.UploadFile>", files.getGenericReturnType().getTypeName());
        Method parts = Request.class.getMethod("parts");
        assertEquals("java.util.List<com.snoworca.naruserve.UploadFile>", parts.getGenericReturnType().getTypeName());
        for (Method m : new Method[] {files, parts}) {
            assertTrue(m.getName(), !m.getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        }
    }

    // IR-MULTIPART-002 AC-2: UploadFile is a public AutoCloseable with java.io content methods
    @Test
    public void IR_MULTIPART_002_AC2_uploadFileDeclaresItsMethods() throws Exception {
        Class<UploadFile> u = UploadFile.class;
        assertTrue(u.isInterface());
        assertTrue(Modifier.isPublic(u.getModifiers()));
        assertTrue(AutoCloseable.class.isAssignableFrom(u));
        assertSame(String.class, u.getMethod("name").getReturnType());
        assertSame(String.class, u.getMethod("filename").getReturnType());
        assertSame(String.class, u.getMethod("contentType").getReturnType());
        assertSame(long.class, u.getMethod("size").getReturnType());
        assertSame(InputStream.class, u.getMethod("inputStream").getReturnType());
        assertSame(void.class, u.getMethod("save", File.class).getReturnType());
        assertSame(void.class, u.getMethod("transferTo", OutputStream.class).getReturnType());
        assertSame(byte[].class, u.getMethod("bytes").getReturnType());
        assertSame(void.class, u.getMethod("close").getReturnType());
        for (Method m : new Method[] {u.getMethod("inputStream"), u.getMethod("save", File.class),
                u.getMethod("transferTo", OutputStream.class), u.getMethod("bytes")}) {
            assertEquals(m.getName(), Arrays.<Class<?>>asList(java.io.IOException.class),
                    Arrays.asList(m.getExceptionTypes()));
        }
        assertEquals(0, u.getMethod("close").getExceptionTypes().length);
        assertTrue(u.getMethod("filename").getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        assertTrue(u.getMethod("contentType").getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
        assertTrue(!u.getMethod("name").getAnnotatedReturnType().isAnnotationPresent(Nullable.class));
    }

    // IR-MULTIPART-002 AC-2: filename and contentType are null for a text part without them
    @Test
    public void IR_MULTIPART_002_AC2_textPartHasNullFilenameAndContentType() {
        app().post("/f", (req, res) -> {
            UploadFile p = req.parts().get(0);
            seen.add(p.name());
            seen.add(String.valueOf(p.filename()));
            seen.add(String.valueOf(p.contentType()));
            seen.add(p.size());
            res.send("ok");
        });
        assertEquals(200, post(MP, multipart(part("a", null, "abc"))).status());
        assertEquals(Arrays.<Object>asList("a", "null", "null", 3L), seen);
    }

    // IR-MULTIPART-002 AC-5: no public or protected signature of the new types names a java.nio.file type
    @Test
    public void IR_MULTIPART_002_AC5_noJavaNioFileInTheNewSignatures() throws Exception {
        List<Method> methods = new ArrayList<>(Arrays.asList(UploadFile.class.getMethods()));
        methods.addAll(Arrays.asList(MultipartOptions.class.getMethods()));
        for (String n : new String[] {"form", "file", "files"}) {
            methods.add(Request.class.getMethod(n, String.class));
        }
        methods.add(Request.class.getMethod("parts"));
        assertTrue(methods.size() > 4);
        for (Method m : methods) {
            String sig = m.toGenericString();
            assertTrue(sig, !sig.contains("java.nio.file"));
        }
    }
}
