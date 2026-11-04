package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** The CORS guidance for cross-origin auth clients in docs/api, Korean page and English mirror (OPS-FLOW-001). */
public class Wave7DocsTest {

    private static String page(String path) throws IOException {
        File f = new File(System.getProperty("user.dir"), "../docs/api/" + path);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[8192];
            for (int r; (r = in.read(b)) > 0;) {
                out.write(b, 0, r);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    /** The CORS section of a page, from its heading to the next level-2 heading. */
    private static String section(String page, String heading) {
        int start = page.indexOf(heading);
        assertTrue("no section " + heading, start >= 0);
        int end = page.indexOf(" ## ", start + heading.length());
        return page.substring(start, end < 0 ? page.length() : end);
    }

    private static void has(String where, String text, String... needles) {
        for (String n : needles) {
            assertTrue(where + " lacks: " + n, text.contains(n));
        }
    }

    private static String ko() throws IOException {
        return section(page("auth.md"), "## 교차 출처 클라이언트와 CORS");
    }

    private static String en() throws IOException {
        return section(page("en/auth.md"), "## Cross-origin clients and CORS");
    }

    // OPS-FLOW-001 AC-1: both pages list Authorization (Bearer and the key scheme), Content-Type, X-CSRF-Token and
    // Naru-Device as allowedHeaders entries, each with its condition
    @Test
    public void OPS_FLOW_001_AC1_allowedHeadersWithConditions() throws IOException {
        has("ko", ko(), "`allowedHeaders`", "| `Authorization` | Bearer", "키 스킴", "| `Content-Type` | JSON 본문",
                "| `X-CSRF-Token` | 쿠키 전달", "| `Naru-Device` | 기기 토큰을 헤더로");
        has("en", en(), "`allowedHeaders`", "| `Authorization` | sending a Bearer", "key scheme",
                "| `Content-Type` | sending a JSON body", "| `X-CSRF-Token` | a client that got cookie delivery",
                "| `Naru-Device` | sending the device token by header");
    }

    // OPS-FLOW-001 AC-2: a cross-origin request carrying Naru-Device while allowedHeaders lacks it is blocked at
    // preflight and never reaches the server
    @Test
    public void OPS_FLOW_001_AC2_missingHeaderBlocksAtPreflight() throws IOException {
        has("ko", ko(), "`allowedHeaders`에 없는 헤더(예: `Naru-Device`)", "preflight에서 막혀", "서버에 닿지 않습니다");
        has("en", en(), "a header that `allowedHeaders` lacks (for example `Naru-Device`)", "blocked at preflight",
                "never reaches the server");
    }

    // OPS-FLOW-001 AC-3: BOTH-mode body-delivery origins belong in allowedOrigins only; adding them to trustedOrigins
    // switches them to cookie delivery although SameSite=Strict cookies are not sent cross-site
    @Test
    public void OPS_FLOW_001_AC3_bodyDeliveryOriginsStayOutOfTrustedOrigins() throws IOException {
        has("ko", ko(), "capacitor://localhost", "`allowedOrigins`에만", "`trustedOrigins`", "쿠키 전달로 바뀌",
                "SameSite=Strict 쿠키는 교차 사이트 요청에 실리지 않으므로");
        has("en", en(), "capacitor://localhost", "belong in `allowedOrigins` only", "`trustedOrigins`",
                "switches them to cookie delivery", "SameSite=Strict cookies are not sent on cross-site requests");
    }

    // OPS-FLOW-001 AC-4: app.cors(...) is registered before auth.install(app)
    @Test
    public void OPS_FLOW_001_AC4_corsBeforeInstall() throws IOException {
        has("ko", ko(), "`app.cors(...)`는 반드시 `auth.install(app)`보다 먼저 등록합니다");
        has("en", en(), "Register `app.cors(...)` before `auth.install(app)`");
    }

    // OPS-FLOW-001 AC-5: the CORS configuration as a Java and a Kotlin example next to the Express equivalent
    @Test
    public void OPS_FLOW_001_AC5_javaKotlinAndExpress() throws IOException {
        for (String[] s : new String[][] {{"ko", ko()}, {"en", en()}}) {
            has(s[0], s[1], "```java", "```kotlin", "app.use(cors({origin:", ".allowedHeaders(\"Authorization\", "
                    + "\"Content-Type\", \"X-CSRF-Token\", \"Naru-Device\")", "auth.install(web)");
        }
    }

    // OPS-FLOW-001 AC-6: a BOTH-mode client checks every token-issuing body: accessToken -> body delivery (Bearer), only
    // csrfToken -> cookie delivery (X-CSRF-Token), and never assumes one shape
    @Test
    public void OPS_FLOW_001_AC6_clientChecksTheResponseShape() throws IOException {
        has("ko", ko(), "`accessToken`이 있으면 본문 전달", "Bearer", "`csrfToken`만 있으면 쿠키 전달", "`X-CSRF-Token`",
                "한 모양을 가정하지 마세요");
        has("en", en(), "`accessToken` present means body delivery", "send Bearer",
                "only `csrfToken` present means cookie delivery", "never assumes one");
    }
}
