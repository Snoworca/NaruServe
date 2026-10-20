package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
// @req OPS-HTTP-004
// @req OPS-APP-002
// @req OPS-JSON-001
// @req OPS-COOKIE-001
// @req OPS-MULTIPART-001
// @req OPS-STATIC-001
// @req OPS-WS-001
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

    // @req OPS-HTTP-004
    // @req OPS-APP-002
    static final List<Skip> SKIPS = Collections.unmodifiableList(Arrays.asList(
            skip("README.md", "RestApp app = RestApp.create();",
                    "app-defined type `User` (the block also calls the app's `userService`)"),
            skip("app.md", "#10", "app-defined type `Database` and `UserService`"),
            skip("express-migration.md", "// Express: let count = 0; 은 안전하지만 Java에서는 아닙니다.",
                    "absent type `AtomicLong` (java.util.concurrent.atomic is not among the fixed imports)"),
            skip("express-migration.md", "User user = req.body(User.class);                         // 객체",
                    "app-defined type `User`"),
            skip("express-migration.md", "app.onStart(ctx -> ctx.register(UserService.class, new UserService()));",
                    "app-defined type `UserService`"),
            skip("express-migration.md", "RestApp app = RestApp.create();", "app-defined type `TodoService` and `Todo`"),
            skip("json.md", "User user = req.body(User.class);                          // 요청 → 객체",
                    "app-defined type `User`"),
            skip("json.md", "public class User {", "absent type `SerializedName` (Gson)"),
            skip("json.md", "Gson gson = new GsonBuilder()", "absent type `Gson`"),
            skip("json.md", "public interface JsonCodec {",
                    "illustrative copy of `JsonCodec` (its `Type` is java.lang.reflect.Type, not imported)"),
            skip("lifecycle.md", "RestApp app = RestApp.create();", "app-defined type `Database` and `UserService`"),
            skip("lifecycle.md", "public interface ServiceRegistry {",
                    "illustrative copy of `ServiceRegistry` and `ApplicationContext` (two public types in one unit)"),
            skip("lifecycle.md", "JdbcUserService impl = new JdbcUserService(db);", "app-defined type `JdbcUserService`"),
            skip("lifecycle.md", "ctx.registerExternal(ConnectionPool.class, sharedPool);   // 종료 때 닫지 않음",
                    "app-defined type `ConnectionPool`"),
            skip("lifecycle.md", "public class ServerService extends Service {", "absent type `android.app.Service`"),
            skip("logging.md", "RotatingFileLogger logger = RotatingFileLogger.builder()",
                    "absent type `RotatingFileLogger` (NaruServe-filelog, not on the root test classpath)"),
            skip("middleware.md", "#1", "illustrative copy of `Middleware` and `Next` (two public types in one unit)"),
            skip("middleware.md", "#3",
                    "app-defined type `User` (the block also calls the app's `auth.isAuthorized` and `admin`)"),
            skip("middleware.md", "app.get(\"/users/:id\", (req, res) -> {",
                    "app-defined type `User` and `NotFoundException` (the block calls the app's `users.find`)"),
            skip("request.md", "// GET /users/42/posts?page=2&tag=a&tag=b",
                    "app-defined variable `postService` (the block calls the app's `postService.find`)"),
            skip("request.md", "User user = req.body(User.class);", "app-defined type `User`"),
            skip("request.md", "User user = req.body(User.class);   // 한 번 읽고",
                    "app-defined type `User` (the block also calls the app's `service`)"),
            skip("request.md", "app.use((req, res, next) -> {",
                    "app-defined type `User` (the block also calls the app's `authenticate`)"),
            skip("request.md", "app.get(\"/users\", (req, res) -> {", "app-defined type `UserService`"),
            skip("response.md", "res.status(201).header(\"X-Request-ID\", id).json(created);",
                    "app-defined variables `id` and `created` (the block sends the app's `created` value)"),
            skip("response.md", "res.json(user);                                        // POJO",
                    "app-defined variables `user`, `user1` and `user2` (the block sends the app's values)"),
            skip("routing.md", "app.route(Method.get().post().delete(), \"/users/:id\", (req, res) -> {",
                    "app-defined methods `find`, `create` and `remove` (the block calls them on the app)"),
            skip("servlet.md", "// 앱 구성: 내장 엔진과 같은 코드",
                    "app-defined type `UserService` (the block is a method declaration, not statements)"),
            skip("sse.md", "app.get(\"/api/progress\", (req, res) -> {", "app-defined type `ProgressService`"),
            skip("sse.md", "client.send(SseEvent.event(\"progress\")", "app-defined variable `client`"),
            skip("sse.md", "#6", "app-defined type `Event` and app-defined variable `eventStore`"),
            skip("websocket.md", "app.ws(\"/chat\", (req, ws) -> {", "app-defined type `ChatService` and `ChatMessage`"),
            skip("websocket.md", "app.use(\"/ws\", (req, res, next) -> {",
                    "app-defined type `Auth` (the block calls `auth.isAuthorized(req)`)")));

    private static final Pattern REASON =
            Pattern.compile("(later-wave method|illustrative copy of|absent type|app-defined type"
                    + "|app-defined variables?|app-defined methods) `[^`]+`.*");
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
    // @req OPS-HTTP-004
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
    // @req OPS-HTTP-004
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
            + "import com.snoworca.naruserve.spi.*;\n"
            + "import java.util.*;\n"
            + "import java.util.concurrent.*;\n"
            + "import java.io.*;\n";

    /** A block that is not a type, placed in {@code DocExample<ordinal>.example()} with the docs' stub names. */
    // @req OPS-HTTP-004
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
                + "    ApplicationContext ctx;\n"
                + "    Middleware auth;\n"
                + "    HttpAdapter myAdapter;\n"
                + "    JsonCodec myCodec;\n"
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
    // @req OPS-HTTP-004
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
                    Skip skip = skipFor(e.getKey(), i + 1, body);
                    if (skip != null) {
                        skipped.put(label + " skip entry " + skip, source);
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

    // SDS-AC-11; OPS-HTTP-004 AC-2
    @Test
    public void sdsAc11_OPS_HTTP_004_AC2_everyJavaFenceOfEveryDocIsExtracted() throws IOException {
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

    // SDS-AC-11; OPS-HTTP-004 AC-3
    @Test
    public void sdsAc11_OPS_HTTP_004_AC3_nonTypeBlocksAreWrappedInTheTemplate() {
        String w = wrap("res.status(204).end();\n", 3);
        for (String part : new String[] {"import com.snoworca.naruserve.*;", "import com.snoworca.naruserve.routing.*;",
                "import com.snoworca.naruserve.spi.*;", "import java.util.*;", "import java.util.concurrent.*;",
                "import java.io.*;", "class DocExample3 {", "ApplicationContext ctx;", "Middleware auth;",
                "HttpAdapter myAdapter;", "JsonCodec myCodec;", "String id,", "Object user;", "RestApp app;", "Request req;",
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

    // SDS-AC-11; OPS-HTTP-004 AC-5
    @Test
    public void sdsAc11_OPS_HTTP_004_AC5_noJavaScriptInJavaFences() throws IOException {
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

    // SDS-AC-11; OPS-HTTP-004 AC-3
    @Test
    public void sdsAc11_OPS_HTTP_004_AC3_compilerRunsInProcessAndReportsErrors() throws IOException {
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

    // SDS-AC-11; OPS-HTTP-004 AC-3
    @Test
    public void sdsAc11_OPS_HTTP_004_AC3_everyNonSkippedDocsBlockCompiles() throws IOException {
        List<String> errors = docsDiagnostics();
        assertTrue("docs/api java blocks do not compile:\n" + String.join("\n", errors), errors.isEmpty());
    }

    // SDS-AC-12; OPS-HTTP-004 AC-4
    @Test
    public void sdsAc12_OPS_HTTP_004_AC4_everySkipNamesFileBlockAndReason() {
        for (Skip s : SKIPS) {
            assertTrue("file " + s, new File(docsDir(), s.file).isFile());
            assertFalse("block selector " + s, s.block.trim().isEmpty());
            if (s.block.startsWith("#")) {
                assertTrue("ordinal " + s, s.block.matches("#[1-9][0-9]*"));
            }
            assertTrue("reason " + s, REASON.matcher(s.reason).matches());
        }
    }

    // SDS-AC-14; OPS-APP-002 AC-2
    @Test
    public void sdsAc14_OPS_APP_002_AC2_everySkippedBlockStillFailsToCompile() throws IOException {
        docsDiagnostics();
        assertTrue("these skipped blocks compile now; drop their skip entries: " + skippedThatCompile,
                skippedThatCompile.isEmpty());
    }

    // SDS-AC-12; OPS-HTTP-004 AC-6
    @Test
    public void sdsAc12_OPS_HTTP_004_AC6_noStaleSkip() throws IOException {
        Map<String, List<String>> fake = new TreeMap<>();
        fake.put("a.md", Arrays.asList("x();\n", "y();\n", "x();\n"));
        List<String> stale = staleSkips(Arrays.asList(skip("a.md", "#4", "absent type `X`"),
                skip("a.md", "z();", "absent type `X`"), skip("a.md", "x();", "absent type `X`"),
                skip("b.md", "#1", "absent type `X`"), skip("a.md", "#2", "absent type `X`")), fake);
        assertEquals(stale.toString(), 4, stale.size());
        assertTrue(stale.get(2), stale.get(2).startsWith("ambiguous"));
        assertEquals(Collections.emptyList(), staleSkips(SKIPS, docs()));
    }

    // SDS-AC-11; OPS-HTTP-004 AC-7
    @Test
    public void sdsAc11_OPS_HTTP_004_AC7_responseAndRoutingBlocksAreCompiled() throws IOException {
        assertTrue(String.join("\n", docsDiagnostics()), docsDiagnostics().isEmpty());
        for (String f : new String[] {"response.md", "routing.md"}) {
            Integer n = compiledPerFile.get(f);
            assertTrue(f + " compiled blocks: " + n, n != null && n > 0);
        }
    }

    /** Wave 4 members: they exist now, so no skip may name one as a missing method or type. */
    private static final String[] WAVE_4_MEMBERS = {"`RestApp.builder()`", "`RestApp.create()`", "`RestApp.listen",
        "`RestApp.shutdown", "`RestApp.use", "`RestApp.onStart", "`RestApp.onStop", "`RestApp.onError",
        "`RestApp.logger", "`RestApp.accessLog", "`Request.service", "`Request.application", "`Middleware`",
        "`Logger`", "`ApplicationContext`", "`ServiceRegistry`", "`JsonCodec`", "`HttpAdapter`"};

    // SDS-AC-13; OPS-APP-002 AC-1
    @Test
    public void sdsAc13_OPS_APP_002_AC1_noSkipNamesAWave4Member() {
        for (Skip s : SKIPS) {
            if (s.reason.startsWith("illustrative copy of")) {
                continue; // a copied declaration of a wave 4 type is a true reason; only "missing" claims are stale
            }
            for (String m : WAVE_4_MEMBERS) {
                assertFalse("wave 4 member as reason: " + s, s.reason.contains(m));
            }
        }
    }

    /** Wave 5 members: they exist now, so no skip may name one as a missing method or type. */
    private static final String[] WAVE_5_MEMBERS = {"`Response.json", "`Request.body", "`TypeRef", "`RestApp.jsonCodec",
        "`RestApp.jsonLimit", "`RestApp.server", "`RestApp.trustProxy", "`ServerOptions", "`Cookie", "`SameSite",
        "`Request.cookie", "`Request.getCookies", "`Response.addCookie"};

    // SDS-AC-4; OPS-JSON-001 AC-5; OPS-APP-002 AC-2
    @Test
    public void sdsAc4_OPS_JSON_001_AC5_noSkipNamesAWave5Member() {
        for (Skip s : SKIPS) {
            if (s.reason.startsWith("illustrative copy of")) {
                continue; // a copied declaration is a true reason; only "missing" claims are stale
            }
            for (String m : WAVE_5_MEMBERS) {
                assertFalse("wave 5 member as reason: " + s, s.reason.contains(m));
            }
        }
    }

    /** Wave 6 members: they exist now, so no skip may name one as a later-wave method. */
    static final List<String> WAVE_SIX_MEMBERS = Collections.unmodifiableList(Arrays.asList("staticFiles", "multipart",
            "form", "file", "files", "parts", "cache", "download", "mimeTypes", "defaultCache", "formLimit"));

    private static final Pattern LATER_WAVE_NAMES = Pattern.compile("later-wave methods? `([^`]+)`((?: and `[^`]+`)*)");

    /** Skips whose reason names a wave 6 member (last dotted segment, argument list dropped) as a later-wave method. */
    static List<String> waveSixMemberReasons(List<Skip> skips) {
        return memberReasons(skips, LATER_WAVE_NAMES, WAVE_SIX_MEMBERS);
    }

    /** Skips whose reason names one of {@code members} in a backticked name of a {@code form} match. */
    private static List<String> memberReasons(List<Skip> skips, Pattern form, List<String> members) {
        List<String> out = new ArrayList<>();
        for (Skip s : skips) {
            Matcher m = form.matcher(s.reason);
            while (m.find()) {
                Matcher names = Pattern.compile("`([^`]+)`").matcher(m.group(0));
                while (names.find()) {
                    String name = names.group(1);
                    int paren = name.indexOf('(');
                    name = paren < 0 ? name : name.substring(0, paren);
                    name = name.substring(name.lastIndexOf('.') + 1);
                    if (members.contains(name)) {
                        out.add(s + ": `" + name + "`");
                    }
                }
            }
        }
        return out;
    }

    /** Wave 7 members and types: they exist now, so no skip may name one as a later-wave method or an absent type. */
    static final List<String> WAVE_SEVEN_MEMBERS = Collections.unmodifiableList(Arrays.asList("ws", "webSocket", "sse",
            "sendJson", "onJson", "onText", "onBinary", "WebSocketSession", "WebSocketHandler", "WebSocketOptions",
            "SseClient", "SseEvent", "SseOptions"));

    private static final Pattern LATER_WAVE_OR_ABSENT_NAMES =
            Pattern.compile("(?:later-wave methods?|absent types?) `([^`]+)`((?: and `[^`]+`)*)");

    /**
     * Skips whose reason names a wave 7 member or type (last dotted segment, argument list dropped) as a later-wave
     * method or an absent type.
     */
    // @req OPS-WS-001
    static List<String> waveSevenMemberReasons(List<Skip> skips) {
        return memberReasons(skips, LATER_WAVE_OR_ABSENT_NAMES, WAVE_SEVEN_MEMBERS);
    }

    // SDS-AC-5 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-8.sds.md; OPS-WS-001 AC-6
    @Test
    public void sdsAc5_OPS_WS_001_AC6_noSkipNamesAWave7MemberAsLaterWaveOrAbsentType() {
        List<String> fake = waveSevenMemberReasons(Arrays.asList(
                skip("a.md", "#1", "later-wave method `Response.sse()`"),
                skip("a.md", "#2", "absent type `SseEvent`"),
                skip("a.md", "#3", "later-wave method `RestApp.staticFiles`"),
                skip("a.md", "#4", "app-defined type `WebSocketSession`"),
                skip("a.md", "#5", "absent types `Gson` and `WebSocketOptions`")));
        assertEquals(fake.toString(), 3, fake.size());
        // SDS-AC-7 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-13.sds.md: no sse.md exclusion is left
        assertEquals(Collections.<String>emptyList(), waveSevenMemberReasons(SKIPS));
    }

    // SDS-AC-7 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-13.sds.md; OPS-SSE-001 AC-4: the sse.md skips
    // keep only singular app-defined reasons, the client block names its undeclared `client`, and every other
    // sse.md block is compiled
    @Test
    public void wave7s13SdsAc7_OPS_SSE_001_AC4_sseSkipsKeepOnlyAppDefinedReasonsAndTheRestCompile()
            throws IOException {
        List<String> reasons = new ArrayList<>();
        int sseSkips = 0;
        for (Skip s : SKIPS) {
            if (s.file.equals("sse.md")) {
                sseSkips++;
                reasons.add(s.reason);
                assertTrue(s.toString(), s.reason.startsWith("app-defined type `")
                        || s.reason.startsWith("app-defined variable `")
                        || s.reason.startsWith("illustrative copy of `"));
                assertTrue(s.toString(), REASON.matcher(s.reason).matches());
            }
        }
        assertTrue(reasons.toString(), reasons.contains("app-defined variable `client`"));
        assertTrue(reasons.toString(), reasons.contains("app-defined type `ProgressService`"));
        assertTrue(String.join("\n", docsDiagnostics()), docsDiagnostics().isEmpty());
        assertEquals(Integer.valueOf(docs().get("sse.md").size() - sseSkips), compiledPerFile.get("sse.md"));
    }

    // SDS-AC-13 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-13.sds.md; OPS-HTTP-007 AC-9: the middleware.md
    // block throwing HttpException(503).withHeader("Retry-After", "1") is compiled, not skipped
    @Test
    public void wave7s13SdsAc13_OPS_HTTP_007_AC9_theWithHeaderBlockIsCompiled() throws IOException {
        int found = 0;
        List<String> blocks = docs().get("middleware.md");
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).contains("throw new HttpException(503).withHeader(\"Retry-After\", \"1\");")) {
                found++;
                assertNull("skipped withHeader block #" + (i + 1), skipFor("middleware.md", i + 1, blocks.get(i)));
            }
        }
        assertEquals(1, found);
        assertTrue(String.join("\n", docsDiagnostics()), docsDiagnostics().isEmpty());
    }

    // SDS-AC-5 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-8.sds.md; OPS-WS-001 AC-6: the websocket.md,
    // lifecycle.md and json.md skips keep only app-defined-type and illustrative-copy reasons, and every other
    // websocket.md block is compiled
    @Test
    public void sdsAc5_OPS_WS_001_AC6_waveSevenFilesKeepOnlyAppDefinedTypeReasons() throws IOException {
        List<String> reasons = new ArrayList<>();
        int wsSkips = 0;
        for (Skip s : SKIPS) {
            if (s.file.equals("websocket.md") || s.file.equals("lifecycle.md") || s.file.equals("json.md")) {
                reasons.add(s.file + " " + s.reason);
            }
            wsSkips += s.file.equals("websocket.md") ? 1 : 0;
        }
        assertTrue(reasons.toString(), reasons.contains("websocket.md app-defined type `ChatService` and `ChatMessage`"));
        assertTrue(reasons.toString(), reasons.contains("lifecycle.md app-defined type `Database` and `UserService`"));
        assertTrue(reasons.toString(), reasons.contains("json.md app-defined type `User`"));
        for (String r : reasons) {
            String reason = r.substring(r.indexOf(' ') + 1);
            assertTrue(r, reason.startsWith("app-defined type `") || reason.startsWith("absent type `")
                    || reason.startsWith("illustrative copy of `"));
        }
        assertEquals("only the ChatService and Auth blocks of websocket.md are skipped", 2, wsSkips);
        assertTrue(String.join("\n", docsDiagnostics()), docsDiagnostics().isEmpty());
        assertEquals(Integer.valueOf(docs().get("websocket.md").size() - wsSkips), compiledPerFile.get("websocket.md"));
    }

    // SDS-AC-3; OPS-STATIC-001 AC-6; OPS-MULTIPART-001 AC-5
    @Test
    public void sdsAc3_OPS_STATIC_001_AC6_noSkipNamesAWave6MemberAsLaterWave() {
        List<String> fake = waveSixMemberReasons(Arrays.asList(
                skip("a.md", "#1", "later-wave method `RestApp.staticFiles` and app-defined type `X`"),
                skip("a.md", "#2", "later-wave method `Request.form(String)`"),
                skip("a.md", "#3", "later-wave method `RestApp.ws`"),
                skip("a.md", "#4", "app-defined type `UploadFile`")));
        assertEquals(fake.toString(), 2, fake.size());
        assertEquals(Collections.<String>emptyList(), waveSixMemberReasons(SKIPS));
    }

    // SDS-AC-3; OPS-MULTIPART-001 AC-5; OPS-STATIC-001 AC-6 — the multipart, form, static and cache blocks compile
    @Test
    public void sdsAc3_OPS_MULTIPART_001_AC5_waveSixBlocksAreCompiled() throws IOException {
        for (Skip s : SKIPS) {
            assertFalse("multipart.md skip: " + s, s.file.equals("multipart.md"));
            assertFalse("static.md skip: " + s, s.file.equals("static.md"));
        }
        assertTrue(String.join("\n", docsDiagnostics()), docsDiagnostics().isEmpty());
        String[] calls = {"req.form(", "req.file(", "req.files(", "req.parts(", "app.multipart(", "app.formLimit(",
            "app.staticFiles(", "res.file(", ".download(", "app.mimeTypes(", "res.cache(", "app.defaultCache("};
        for (String call : calls) {
            int compiled = 0;
            for (String f : new String[] {"multipart.md", "request.md", "static.md", "response.md"}) {
                List<String> blocks = docs().get(f);
                for (int i = 0; i < blocks.size(); i++) {
                    if (blocks.get(i).contains(call) && skipFor(f, i + 1, blocks.get(i)) == null) {
                        compiled++;
                    }
                }
            }
            assertTrue("a compiled block calls " + call, compiled > 0);
        }
        for (String f : new String[] {"multipart.md", "static.md"}) {
            assertEquals(f, Integer.valueOf(docs().get(f).size()), compiledPerFile.get(f));
        }
    }

    // SDS-AC-3; OPS-STATIC-001 AC-6 — app.md and express-migration.md keep only app-defined-type reasons
    @Test
    public void sdsAc3_OPS_STATIC_001_AC6_appAndExpressSkipsKeepOnlyAppDefinedTypes() {
        for (Skip s : SKIPS) {
            if (s.file.equals("app.md") || s.file.equals("express-migration.md")
                    && s.block.equals("RestApp app = RestApp.create();")) {
                assertTrue(s.toString(), s.reason.startsWith("app-defined type `"));
            }
        }
    }

    // SDS-AC-4; OPS-JSON-001 AC-5; OPS-APP-002 AC-2 — a skipped block calling res.json/req.body names what still fails
    @Test
    public void sdsAc4_OPS_JSON_001_AC5_skippedJsonBlocksNameTheReasonThatStillApplies() throws IOException {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : docs().entrySet()) {
            List<String> blocks = e.getValue();
            for (int i = 0; i < blocks.size(); i++) {
                String body = blocks.get(i);
                Skip s = skipFor(e.getKey(), i + 1, body);
                if (s != null && (body.contains(".json(") || body.contains("req.body("))
                        && (s.reason.contains("Response.json") || s.reason.contains("Request.body"))) {
                    stale.add(s.toString());
                }
                // Every backticked name of a reason (last dotted segment, without its argument list) is in the block.
                java.util.regex.Matcher named = s == null ? null : Pattern.compile("`([^`]+)`").matcher(s.reason);
                while (named != null && named.find()) {
                    String name = named.group(1);
                    int paren = name.indexOf('(');
                    name = paren < 0 ? name : name.substring(0, paren);
                    name = name.substring(name.lastIndexOf('.') + 1);
                    if (!body.contains(name)) {
                        stale.add(s + ": `" + name + "` is not in the block");
                    }
                }
            }
        }
        assertEquals(Collections.<String>emptyList(), stale);
    }

    // SDS-AC-6; OPS-COOKIE-001 AC-5
    @Test
    public void sdsAc6_OPS_COOKIE_001_AC5_cookieBlocksCompileWithoutSkips() throws IOException {
        for (Skip s : SKIPS) {
            assertFalse("cookie.md skip: " + s, s.file.equals("cookie.md"));
        }
        assertTrue(String.join("\n", docsDiagnostics()), docsDiagnostics().isEmpty());
        Integer n = compiledPerFile.get("cookie.md");
        assertEquals(Integer.valueOf(docs().get("cookie.md").size()), n);
        assertTrue("cookie.md compiled blocks: " + n, n > 0);
    }

    // OPS-ENGINE-002 AC-6 — the blocks calling server, trustProxy or ServerOptions are compiled, not skipped
    @Test
    public void OPS_ENGINE_002_AC6_serverSettingBlocksAreCompiled() throws IOException {
        int found = 0;
        for (Map.Entry<String, List<String>> e : docs().entrySet()) {
            List<String> blocks = e.getValue();
            for (int i = 0; i < blocks.size(); i++) {
                String body = blocks.get(i);
                if (body.contains(".server(") || body.contains("trustProxy(") || body.contains("ServerOptions")) {
                    found++;
                    assertNull("skipped server-setting block " + e.getKey() + " #" + (i + 1),
                            skipFor(e.getKey(), i + 1, body));
                }
            }
        }
        assertTrue("docs/api has Java blocks calling server or trustProxy", found > 0);
        assertTrue(String.join("\n", docsDiagnostics()), docsDiagnostics().isEmpty());
    }

    // SDS-AC-12; OPS-HTTP-004 AC-4
    @Test
    public void sdsAc12_OPS_HTTP_004_AC4_appDefinedTypesAreNamedReasons() {
        boolean appDefined = false;
        for (Skip s : SKIPS) {
            appDefined |= s.reason.startsWith("app-defined type `");
        }
        assertTrue("an app-defined example type such as UserService or Database is a named reason", appDefined);
        assertTrue(REASON.matcher("app-defined type `UserService`").matches());
        assertFalse(REASON.matcher("app-defined type").matches());
    }
}
