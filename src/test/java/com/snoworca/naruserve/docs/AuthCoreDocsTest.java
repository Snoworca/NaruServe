package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The 0.11.0 Core changes (C4, C6a, C6b, C7) in the bilingual docs/api guides, found by fixed phrases: one method
 * per requirement acceptance criterion.
 */
public class AuthCoreDocsTest {

    private static final String[] TRUST_PAGES = {"app.md", "request.md", "express-migration.md"};

    private static String doc(String name) throws IOException {
        String root = System.getProperty("naruserve.rootDir");
        assertNotNull("naruserve.rootDir system property", root);
        File f = new File(root, "docs/api/" + name);
        assertTrue(f.getPath(), f.isFile());
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static String lineWith(String file, String text, String needle) {
        for (String line : text.split("\n")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        throw new AssertionError(file + " has no line with " + needle);
    }

    private static void contains(String file, String text, String... needles) {
        for (String n : needles) {
            assertTrue(file + " lacks " + n, text.contains(n));
        }
    }

    private static List<String> fences(String text, String info) {
        List<String> out = new ArrayList<>();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("```" + info)) {
                StringBuilder b = new StringBuilder();
                for (i++; i < lines.length && !lines[i].trim().equals("```"); i++) {
                    b.append(lines[i]).append('\n');
                }
                out.add(b.toString());
            }
        }
        return out;
    }

    private static boolean anyFence(String text, String info, String... needles) {
        for (String f : fences(text, info)) {
            boolean all = true;
            for (String n : needles) {
                all &= f.contains(n);
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    // CON-ENGINE-001 AC-3: the legacy-trust warning stands on the three pages in both languages
    @Test
    public void CON_ENGINE_001_AC3_legacyTrustWarningInBothLanguages() throws IOException {
        for (String page : TRUST_PAGES) {
            String ko = doc(page);
            String line = lineWith(page, ko, "클라이언트가 고를 수 있는");
            contains(page, line, "`trustProxy(true)`", "호환", "FAIL", "`PROD`", "R32", "trustProxyHops", "trustProxyCidrs",
                    "#프록시-신뢰");
            String en = doc("en/" + page);
            String enLine = lineWith("en/" + page, en, "client-controlled");
            contains("en/" + page, enLine, "`trustProxy(true)`", "compatibility", "FAIL", "`PROD`", "R32",
                    "trustProxyHops", "trustProxyCidrs", "#proxy-trust");
        }
    }

    // IR-ENGINE-003 AC-7: both methods, the Express mapping, the hop precondition, the nginx lines and the
    // unsupported X-Forwarded-Proto pass-through on the three pages in both languages
    @Test
    public void IR_ENGINE_003_AC7_trustModesDocumentedInBothLanguages() throws IOException {
        for (String page : TRUST_PAGES) {
            for (String file : new String[] {page, "en/" + page}) {
                String text = doc(file);
                contains(file, text, "trustProxyHops(1)", "trustProxyCidrs(", "app.set('trust proxy', 1)",
                        "proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;",
                        "proxy_set_header X-Forwarded-Proto $scheme;");
            }
            String ko = doc(page);
            contains(page, ko, "로드 밸런서를 거쳐서만", "덮어쓰지 않고 그대로 넘기는", "지원하지 않");
            String en = doc("en/" + page);
            contains("en/" + page, en, "only through the load balancer", "without overwriting it", "unsupported");
        }
        // OPS-ENGINE-002 AC-4 facts stay: the legacy setter is still documented on app.md and request.md
        assertTrue(doc("app.md").contains("`app.trustProxy(true)`"));
        assertTrue(doc("request.md").contains("`app.trustProxy(true)`"));
        String app = doc("app.md");
        contains("app.md", app, "### 프록시 신뢰", "`trustProxyHops()`", "`trustProxyCidrs()`", "`trustProxy()`");
        contains("en/app.md", doc("en/app.md"), "### Proxy trust", "`trustProxyHops()`", "`trustProxyCidrs()`",
                "`trustProxy()`");
    }

    // IR-HTTP-010 AC-4: socketAddress() and requestId() on the request page, with Java and Kotlin examples
    @Test
    public void IR_HTTP_010_AC4_socketAddressAndRequestIdDocumented() throws IOException {
        String ko = doc("request.md");
        contains("request.md", lineWith("request.md", ko, "| `socketAddress()`"), "`req.socket.remoteAddress`");
        contains("request.md", lineWith("request.md", ko, "| `requestId()`"), "Express");
        contains("request.md", ko, "프록시 설정과 헤더를 모두 무시", "Express에는 기본 요청 ID가 없",
                "표준 오류 본문의 `requestId`", "접근 로그의 `rid`");
        assertTrue(anyFence(ko, "java", "req.socketAddress()", "req.requestId()"));
        assertTrue(anyFence(ko, "kotlin", "req.socketAddress()", "req.requestId()"));
        String en = doc("en/request.md");
        contains("en/request.md", lineWith("en/request.md", en, "| `socketAddress()`"), "`req.socket.remoteAddress`");
        contains("en/request.md", lineWith("en/request.md", en, "| `requestId()`"), "Express");
        contains("en/request.md", en, "ignores every proxy setting and header", "Express has no built-in request id",
                "`requestId` of the standard error body", "`rid` of the access log");
        assertTrue(anyFence(en, "java", "req.socketAddress()", "req.requestId()"));
        assertTrue(anyFence(en, "kotlin", "req.socketAddress()", "req.requestId()"));
    }

    private static final String[] KINDS = {"ROUTE", "WEBSOCKET", "STATIC", "ROUTER_MOUNT", "GROUP", "CORS",
        "MIDDLEWARE", "PATH_MIDDLEWARE", "ERROR_MIDDLEWARE", "PARAM"};
    private static final String[] ACCESSORS = {"`getKind()`", "`getMethod()`", "`getPath()`", "`getPathSource()`",
        "`getTarget()`", "`getMount()`", "`isStringPath()`"};

    // IR-APP-008 AC-7: registrations() and onRegistration on the app page in both languages
    @Test
    public void IR_APP_008_AC7_registrationListAndWatcherDocumented() throws IOException {
        String ko = doc("app.md");
        contains("app.md", ko, "## 등록 목록과 감시자", "`registrations()`", "`onRegistration(listener)`",
                "검증 → 감시자 → 적용 → 기록", "app._router.stack");
        String en = doc("en/app.md");
        contains("en/app.md", en, "## Registration list and watcher", "`registrations()`", "`onRegistration(listener)`",
                "validate → watcher → apply → record", "app._router.stack");
        for (String file : new String[] {"app.md", "en/app.md"}) {
            String text = doc(file);
            for (String kind : KINDS) {
                assertTrue(file + " kind row " + kind, text.contains("| `" + kind + "` |"));
            }
            contains(file, text, ACCESSORS);
            assertTrue(file + " Java example", anyFence(text, "java", "onRegistration", "registrations()"));
            assertTrue(file + " Kotlin example", anyFence(text, "kotlin", "onRegistration", "registrations()"));
            assertFalse(file + " keeps a marker", text.contains("(미확정)"));
        }
    }

    // SEC-SVL-001 AC-5: the observed container values are recorded on the servlet page in both languages
    @Test
    public void SEC_SVL_001_AC5_observedContainerValuesRecorded() throws IOException {
        String ko = doc("servlet.md");
        contains("servlet.md", lineWith("servlet.md", ko, "`socketAddress()`"), "`getRemoteAddr()`", "Jetty 9.4");
        contains("servlet.md", ko, "`trustProxyHops`", "`trustProxyCidrs`", "RemoteIpValve", "ForwardedRequestCustomizer",
                "`getRemoteHost()`");
        String en = doc("en/servlet.md");
        contains("en/servlet.md", lineWith("en/servlet.md", en, "`socketAddress()`"), "`getRemoteAddr()`", "Jetty 9.4");
        contains("en/servlet.md", en, "`trustProxyHops`", "`trustProxyCidrs`", "RemoteIpValve",
                "ForwardedRequestCustomizer", "`getRemoteHost()`");
    }
}
