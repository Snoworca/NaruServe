package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Every ```java block of docs/api compiles against NaruServe-api, or sits on {@link #SKIPS} with a reason. */
// @req OPS-HTTP-002
public final class DocsExamplesCompileTest {

    /** One skipped block: its file, a selector ({@code #n} ordinal or the block's first line) and one reason. */
    static final class Skip {
        final String file;
        final String block;
        final String reason;

        Skip(String file, String block, String reason) {
            this.file = file;
            this.block = block;
            this.reason = reason;
        }

        boolean matches(int ordinal, String body) {
            return block.startsWith("#") ? block.equals("#" + ordinal) : block.equals(firstLine(body));
        }

        @Override
        public String toString() {
            return file + " " + block + " (" + reason + ")";
        }
    }

    private static Skip skip(String file, String block, String reason) {
        return new Skip(file, block, reason);
    }

    private static final String JSON = "later-wave method `Response.json(Object)`";
    private static final String CREATE = "later-wave method `RestApp.create()`";
    private static final String USE = "later-wave method `RestApp.use(Middleware)`";
    private static final String BODY = "later-wave method `Request.body(Class)`";

    // @req OPS-HTTP-002
    static final List<Skip> SKIPS = Collections.unmodifiableList(Arrays.asList(
            skip("README.md", "RestApp app = RestApp.create();", CREATE),
            skip("app.md", "RestApp app = RestApp.builder()", "later-wave method `RestApp.builder()`"),
            skip("app.md", "ServerHandle server = app.listen(8080);", "later-wave method `RestApp.listen(int)`"),
            skip("app.md", "app.shutdown(10, TimeUnit.SECONDS);", "later-wave method `RestApp.shutdown(long, TimeUnit)`"),
            skip("app.md", "#7", CREATE),
            skip("cookie.md", "Cookie cookie = new Cookie(\"session\", token);", "absent type `Cookie`"),
            skip("cookie.md", "Cookie session = req.cookie(\"session\");   // 없으면 null", "absent type `Cookie`"),
            skip("cookie.md", "Cookie expired = new Cookie(\"session\", \"\");", "absent type `Cookie`"),
            skip("express-migration.md", "// Express: let count = 0; 은 안전하지만 Java에서는 아닙니다.", JSON),
            skip("express-migration.md", "User user = req.body(User.class);                         // 객체", BODY),
            skip("express-migration.md", "app.onStart(ctx -> ctx.register(UserService.class, new UserService()));",
                    "later-wave method `RestApp.onStart`"),
            skip("express-migration.md", "app.use(\"/api\", auth);   // Express: 1번째, NaruServe: 2번째", USE),
            skip("express-migration.md", "RestApp app = RestApp.create();", CREATE),
            skip("json.md", "User user = req.body(User.class);                          // 요청 → 객체", BODY),
            skip("json.md", "public class User {", "absent type `SerializedName` (Gson)"),
            skip("json.md", "Gson gson = new GsonBuilder()", "absent type `Gson`"),
            skip("json.md", "public interface JsonCodec {", "absent type `JsonCodec`"),
            skip("lifecycle.md", "RestApp app = RestApp.create();", CREATE),
            skip("lifecycle.md", "public interface ServiceRegistry {", "absent type `Logger`"),
            skip("lifecycle.md", "JdbcUserService impl = new JdbcUserService(db);", "absent type `ApplicationContext`"),
            skip("lifecycle.md", "ctx.attribute(\"appVersion\", \"1.0.0\");", "absent type `ApplicationContext`"),
            skip("lifecycle.md", "public class ServerService extends Service {", "absent type `android.app.Service`"),
            skip("logging.md", "app.accessLog(options -> {", "later-wave method `RestApp.accessLog`"),
            skip("logging.md", "app.logger().info(\"Server initialized\");", "later-wave method `RestApp.logger()`"),
            skip("middleware.md", "@FunctionalInterface", "absent type `Middleware`"),
            skip("middleware.md", "#2", USE),
            skip("middleware.md", "app.use(\"/api\", auth);       // 2번째로 실행", USE),
            skip("middleware.md", "#4", USE),
            skip("middleware.md", "app.get(\"/users/:id\", (req, res) -> {", JSON),
            skip("middleware.md", "app.onError((error, req, res) -> {", "later-wave method `RestApp.onError`"),
            skip("multipart.md", "app.post(\"/api/upload\", (req, res) -> {", "later-wave method `Request.form(String)`"),
            skip("multipart.md", "app.multipart(config -> {", "later-wave method `RestApp.multipart`"),
            skip("request.md", "// GET /users/42/posts?page=2&tag=a&tag=b", JSON),
            skip("request.md", "User user = req.body(User.class);", BODY),
            skip("request.md", "User user = req.body(User.class);   // 한 번 읽고", BODY),
            skip("request.md", "Cookie session = req.cookie(\"session\");", "absent type `Cookie`"),
            skip("request.md", "app.use((req, res, next) -> {", USE),
            skip("request.md", "app.get(\"/users\", (req, res) -> {", "later-wave method `Request.service(Class)`"),
            skip("response.md", "res.status(201).header(\"X-Request-ID\", id).json(created);", JSON),
            skip("response.md", "res.json(user);                                        // POJO", JSON),
            skip("response.md", "app.get(\"/report\", (req, res) -> res.file(new File(\"/data/report.pdf\")));",
                    "later-wave method `Response.file(File)`"),
            skip("routing.md", "app.route(Method.get().post().delete(), \"/users/:id\", (req, res) -> {", JSON),
            skip("servlet.md", "// 앱 구성: 내장 엔진과 같은 코드", "later-wave method `RestApp.onStart`"),
            skip("sse.md", "#1", "later-wave method `Response.sse()`"),
            skip("sse.md", "app.get(\"/api/progress\", (req, res) -> {", "later-wave method `Response.sse()`"),
            skip("sse.md", "client.send(SseEvent.event(\"progress\")", "absent type `SseEvent`"),
            skip("sse.md", "#4", "later-wave method `Response.sse()`"),
            skip("static.md", "app.staticFiles(\"/\", new File(\"./www\"));", "later-wave method `RestApp.staticFiles`"),
            skip("static.md", "app.staticFiles(\"/\", new File(\"./www\"), options -> {",
                    "later-wave method `RestApp.staticFiles`"),
            skip("static.md", "app.mimeTypes()", "later-wave method `RestApp.mimeTypes()`"),
            skip("static.md", "res.cache(CachePolicy.noStore()).json(privateData);", "later-wave method `Response.cache`"),
            skip("websocket.md", "app.ws(\"/ws\", (req, ws) -> {", "later-wave method `RestApp.ws`"),
            skip("websocket.md", "app.ws(\"/chat\", (req, ws) -> {", "later-wave method `RestApp.ws`"),
            skip("websocket.md", "app.use(\"/ws\", (req, res, next) -> {", USE)));

    private static final Pattern REASON =
            Pattern.compile("(later-wave method|illustrative copy of|absent type) `[^`]+`.*");
    private static final Pattern TYPE_DECL = Pattern.compile(
            "^(@\\w+(\\([^)]*\\))?\\s+)*((public|protected|private|abstract|final|static|sealed)\\s+)*"
                    + "(class|interface|enum|@interface)\\s+(\\w+).*");
    private static final Pattern ONLY_ANNOTATION = Pattern.compile("^@\\w+(\\([^)]*\\))?$");
    private static final Pattern JS_LINE = Pattern.compile(
            ".*=>.*|^\\s*(const|let|var|function)\\s.*|.*require\\(.*|.*'[^'\\\\]{2,}'.*");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Map<String, List<String>> docs;
    private static Map<String, Integer> compiledPerFile;
    private static List<String> docsDiagnostics;
    private static List<String> skippedThatCompile;

    private static File docsDir() {
        String root = System.getProperty("naruserve.rootDir");
        assertNotNull("naruserve.rootDir system property", root);
        return new File(root, "docs/api");
    }

    private static String read(File f) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            for (int n = in.read(buf); n > 0; n = in.read(buf)) {
                out.write(buf, 0, n);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String firstLine(String block) {
        for (String l : block.split("\n")) {
            if (!l.trim().isEmpty()) {
                return l;
            }
        }
        return "";
    }

    /** Every fenced block opened by a {@code java} info string, in file order. */
    // @req OPS-HTTP-002
    static List<String> extractJavaBlocks(File md) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder cur = null;
        for (String line : read(md).split("\n", -1)) {
            if (cur == null) {
                if (line.trim().equals("```java")) {
                    cur = new StringBuilder();
                }
            } else if (line.trim().startsWith("```")) {
                out.add(cur.toString());
                cur = null;
            } else {
                cur.append(line).append('\n');
            }
        }
        return out;
    }

    /** True when the first line that is not blank, an import, a comment or a lone annotation declares a type. */
    // @req OPS-HTTP-002
    static boolean isTopLevelType(String block) {
        for (String raw : block.split("\n")) {
            String l = raw.trim();
            if (l.isEmpty() || l.startsWith("import ") || l.startsWith("//") || l.startsWith("/*")
                    || l.startsWith("*") || ONLY_ANNOTATION.matcher(l).matches()) {
                continue;
            }
            return TYPE_DECL.matcher(l).matches();
        }
        return false;
    }

    private static final String IMPORTS = "import com.snoworca.naruserve.*;\n"
            + "import com.snoworca.naruserve.routing.*;\n"
            + "import java.util.*;\n"
            + "import java.io.*;\n";

    /** A block that is not a type, placed in {@code DocExample<ordinal>.example()} with the docs' stub names. */
    // @req OPS-HTTP-002
    static String wrap(String block, int ordinal) {
        return "package docexample.u" + ordinal + ";\n"
                + IMPORTS
                + "@SuppressWarnings(\"unused\")\n"
                + "class DocExample" + ordinal + " {\n"
                + "    RestApp app;\n"
                + "    Request req;\n"
                + "    Response res;\n"
                + "    RouteHandler getUser, getMe, updateUser, deleteUser, list, h, h1, h2;\n"
                + "    String id, token;\n"
                + "    Object user;\n"
                + "    void example() throws Exception {\n"
                + block
                + "    }\n"
                + "}\n";
    }

    /** A top-level type block as its own compilation unit in its own package. */
    private static String unit(String block, int ordinal) {
        return "package docexample.u" + ordinal + ";\n" + IMPORTS + block;
    }

    private static String unitFileName(String source) {
        for (String l : source.split("\n")) {
            Matcher m = TYPE_DECL.matcher(l.trim());
            if (m.matches()) {
                return m.group(6) + ".java";
            }
        }
        throw new IllegalArgumentException("no type declaration in\n" + source);
    }

    /**
     * Compiles the units (label to source) in process with {@code --release 8} against the test JVM's
     * {@code java.class.path}; returns one entry per error, prefixed by the unit's label. Fails with no compiler.
     */
    // @req OPS-HTTP-002
    static List<String> compile(Map<String, String> units) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull("no system Java compiler; run the tests on a JDK", javac);
        File dir = java.nio.file.Files.createTempDirectory("docexamples").toFile();
        try {
            File src = new File(dir, "src");
            File out = new File(dir, "classes");
            assertTrue(out.mkdirs());
            Map<String, String> labelByPath = new LinkedHashMap<>();
            List<File> files = new ArrayList<>();
            int i = 0;
            for (Map.Entry<String, String> e : units.entrySet()) {
                File pkg = new File(src, "s" + (i++));
                assertTrue(pkg.mkdirs());
                File f = new File(pkg, unitFileName(e.getValue()));
                try (OutputStream o = new FileOutputStream(f)) {
                    o.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                }
                files.add(f);
                labelByPath.put(f.getAbsolutePath(), e.getKey());
            }
            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            List<String> errors = new ArrayList<>();
            try (StandardJavaFileManager fm = javac.getStandardFileManager(diags, Locale.ROOT, StandardCharsets.UTF_8)) {
                List<String> options = Arrays.asList("--release", "8", "-proc:none", "-encoding", "UTF-8",
                        "-classpath", System.getProperty("java.class.path"), "-d", out.getAbsolutePath());
                Boolean ok = javac.getTask(null, fm, diags, options, null, fm.getJavaFileObjectsFromFiles(files)).call();
                for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                    if (d.getKind() != Diagnostic.Kind.ERROR) {
                        continue;
                    }
                    String label = d.getSource() == null ? "(no source)"
                            : labelByPath.get(new File(d.getSource().toUri()).getAbsolutePath());
                    errors.add(label + " line " + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT));
                }
                if (!ok && errors.isEmpty()) {
                    errors.add("javac failed without an error diagnostic");
                }
            }
            return errors;
        } finally {
            delete(dir);
        }
    }

    private static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                delete(k);
            }
        }
        f.delete();
    }

    private static synchronized Map<String, List<String>> docs() throws IOException {
        if (docs == null) {
            File[] mds = docsDir().listFiles((d, n) -> n.endsWith(".md"));
            assertNotNull(mds);
            Map<String, List<String>> m = new TreeMap<>();
            for (File md : mds) {
                m.put(md.getName(), extractJavaBlocks(md));
            }
            docs = m;
        }
        return docs;
    }

    private static Skip skipFor(String file, int ordinal, String body) {
        for (Skip s : SKIPS) {
            if (s.file.equals(file) && s.matches(ordinal, body)) {
                return s;
            }
        }
        return null;
    }

    /** Entries that match no block of their file, or a first-line selector matching more than one block. */
    static List<String> staleSkips(List<Skip> skips, Map<String, List<String>> blocksByFile) {
        List<String> stale = new ArrayList<>();
        for (Skip s : skips) {
            List<String> blocks = blocksByFile.get(s.file);
            int hits = 0;
            if (blocks != null) {
                for (int i = 0; i < blocks.size(); i++) {
                    if (s.matches(i + 1, blocks.get(i))) {
                        hits++;
                    }
                }
            }
            if (hits == 0) {
                stale.add("stale skip, matches no block: " + s);
            } else if (hits > 1) {
                stale.add("ambiguous skip, use an ordinal: " + s);
            }
        }
        return stale;
    }

    /** Compiles every non-skipped docs block once; per-file compiled counts are kept for SDS-AC-11. */
    private static synchronized List<String> docsDiagnostics() throws IOException {
        if (docsDiagnostics == null) {
            Map<String, String> units = new LinkedHashMap<>();
            Map<String, String> skipped = new LinkedHashMap<>();
            Map<String, Integer> counts = new TreeMap<>();
            int unitNo = 0;
            for (Map.Entry<String, List<String>> e : docs().entrySet()) {
                List<String> blocks = e.getValue();
                for (int i = 0; i < blocks.size(); i++) {
                    String body = blocks.get(i);
                    unitNo++;
                    String label = e.getKey() + " #" + (i + 1) + " (" + firstLine(body).trim() + ")";
                    String source = isTopLevelType(body) ? unit(body, unitNo) : wrap(body, unitNo);
                    if (skipFor(e.getKey(), i + 1, body) != null) {
                        skipped.put(label, source);
                        continue;
                    }
                    units.put(label, source);
                    Integer c = counts.get(e.getKey());
                    counts.put(e.getKey(), c == null ? 1 : c + 1);
                }
            }
            docsDiagnostics = compile(units);
            compiledPerFile = counts;
            // A skip whose block now compiles has an untrue reason (the later-wave method arrived). One javac run
            // per block: javac stops attributing every file once an earlier phase reports an error in any file.
            List<String> clean = new ArrayList<>();
            for (Map.Entry<String, String> e : skipped.entrySet()) {
                if (compile(Collections.singletonMap(e.getKey(), e.getValue())).isEmpty()) {
                    clean.add(e.getKey());
                }
            }
            skippedThatCompile = clean;
        }
        return docsDiagnostics;
    }

    private File md(String content) throws IOException {
        File f = tmp.newFile("sample.md");
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    // SDS-AC-7; OPS-HTTP-002 AC-2
    @Test
    public void sdsAc7_OPS_HTTP_002_AC2_everyJavaFenceOfEveryDocIsExtracted() throws IOException {
        File f = md("# t\n```java\nint a = 1;\n```\n\n```kotlin\nval b = 2\n```\n```java\nint c = 3;\nint d = 4;\n```\n");
        assertEquals(Arrays.asList("int a = 1;\n", "int c = 3;\nint d = 4;\n"), extractJavaBlocks(f));
        Map<String, List<String>> all = docs();
        assertFalse(all.isEmpty());
        for (Map.Entry<String, List<String>> e : all.entrySet()) {
            int fences = 0;
            for (String l : read(new File(docsDir(), e.getKey())).split("\n")) {
                if (l.trim().equals("```java")) {
                    fences++;
                }
            }
            assertEquals(e.getKey(), fences, e.getValue().size());
        }
    }

    // SDS-AC-7; OPS-HTTP-002 AC-3
    @Test
    public void sdsAc7_OPS_HTTP_002_AC3_nonTypeBlocksAreWrappedInTheTemplate() {
        String w = wrap("res.status(204).end();\n", 3);
        for (String part : new String[] {"import com.snoworca.naruserve.*;", "import com.snoworca.naruserve.routing.*;",
                "import java.util.*;", "import java.io.*;", "class DocExample3 {", "RestApp app;", "Request req;",
                "Response res;", "RouteHandler getUser,", "void example() throws Exception {",
                "res.status(204).end();"}) {
            assertTrue(part, w.contains(part));
        }
        assertTrue(isTopLevelType("@FunctionalInterface\npublic interface RouteHandler {\n}\n"));
        assertTrue(isTopLevelType("import java.io.*;\n// c\npublic enum Method { GET }\n"));
        assertTrue(isTopLevelType("public @interface A {}\n"));
        assertTrue(isTopLevelType("class X {}\n"));
        assertFalse(isTopLevelType("res.status(204).end();\n"));
        assertFalse(isTopLevelType("static void configure(RestApp app) {\n}\n"));
        assertFalse(isTopLevelType("MethodSet set = Method.get();\n"));
    }

    // SDS-AC-7; OPS-HTTP-002 AC-5
    @Test
    public void sdsAc7_OPS_HTTP_002_AC5_noJavaScriptInJavaFences() throws IOException {
        List<String> js = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : docs().entrySet()) {
            List<String> blocks = e.getValue();
            for (int i = 0; i < blocks.size(); i++) {
                for (String l : blocks.get(i).split("\n")) {
                    if (!l.trim().startsWith("//") && JS_LINE.matcher(l).matches()) {
                        js.add(e.getKey() + " #" + (i + 1) + ": " + l);
                        break;
                    }
                }
            }
        }
        assertTrue("relabel these fences ```js: " + js, js.isEmpty());
    }

    // SDS-AC-8; OPS-HTTP-002 AC-3
    @Test
    public void sdsAc8_OPS_HTTP_002_AC3_compilerRunsInProcessAndReportsErrors() throws IOException {
        Map<String, String> units = new LinkedHashMap<>();
        units.put("good", wrap("app.get(\"/x\", (req, res) -> res.send(\"x\"));\n", 1));
        assertEquals(Collections.emptyList(), compile(units));
        units.put("bad", wrap("res.noSuchMethod();\n", 2));
        List<String> errors = compile(units);
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).startsWith("bad line "));
        // --release 8 rejects a Java 10 local `var`.
        units.remove("bad");
        units.put("java10", wrap("var x = 1;\n", 3));
        assertFalse(compile(units).isEmpty());
    }

    // SDS-AC-8; OPS-HTTP-002 AC-3
    @Test
    public void sdsAc8_OPS_HTTP_002_AC3_everyNonSkippedDocsBlockCompiles() throws IOException {
        List<String> errors = docsDiagnostics();
        assertTrue("docs/api java blocks do not compile:\n" + String.join("\n", errors), errors.isEmpty());
    }

    // SDS-AC-9; OPS-HTTP-002 AC-4
    @Test
    public void sdsAc9_OPS_HTTP_002_AC4_everySkipNamesFileBlockAndReason() {
        for (Skip s : SKIPS) {
            assertTrue("file " + s, new File(docsDir(), s.file).isFile());
            assertFalse("block selector " + s, s.block.trim().isEmpty());
            if (s.block.startsWith("#")) {
                assertTrue("ordinal " + s, s.block.matches("#[1-9][0-9]*"));
            }
            assertTrue("reason " + s, REASON.matcher(s.reason).matches());
        }
    }

    // SDS-AC-9; OPS-HTTP-002 AC-4
    @Test
    public void sdsAc9_OPS_HTTP_002_AC4_everySkippedBlockStillFailsToCompile() throws IOException {
        docsDiagnostics();
        assertTrue("these skipped blocks compile now; drop their skip entries: " + skippedThatCompile,
                skippedThatCompile.isEmpty());
    }

    // SDS-AC-10; OPS-HTTP-002 AC-6
    @Test
    public void sdsAc10_OPS_HTTP_002_AC6_noStaleSkip() throws IOException {
        Map<String, List<String>> fake = new TreeMap<>();
        fake.put("a.md", Arrays.asList("x();\n", "y();\n", "x();\n"));
        List<String> stale = staleSkips(Arrays.asList(skip("a.md", "#4", "absent type `X`"),
                skip("a.md", "z();", "absent type `X`"), skip("a.md", "x();", "absent type `X`"),
                skip("b.md", "#1", "absent type `X`"), skip("a.md", "#2", "absent type `X`")), fake);
        assertEquals(stale.toString(), 4, stale.size());
        assertTrue(stale.get(2), stale.get(2).startsWith("ambiguous"));
        assertEquals(Collections.emptyList(), staleSkips(SKIPS, docs()));
    }

    // SDS-AC-11; OPS-HTTP-002 AC-7
    @Test
    public void sdsAc11_OPS_HTTP_002_AC7_responseAndRoutingBlocksAreCompiled() throws IOException {
        assertTrue(String.join("\n", docsDiagnostics()), docsDiagnostics().isEmpty());
        for (String f : new String[] {"response.md", "routing.md"}) {
            Integer n = compiledPerFile.get(f);
            assertTrue(f + " compiled blocks: " + n, n != null && n > 0);
        }
    }
}
