package com.snoworca.naruserve.docs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;
import static org.junit.Assert.*;

/** OPS-DX-002 AC-1..4: examples must not contradict implemented behavior. */
// @req OPS-REL-008 AC-1 AC-2 AC-3
public class UsabilityDocsTest {
    private String doc(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("docs/api", name)), StandardCharsets.UTF_8);
    }
    // OPS-DX-002 AC-2; OPS-DX-002 AC-3
    @Test public void errorAndOriginGuidesDescribeSeparateMechanisms() throws Exception {
        assertFalse("Common unhandled errors render HTML rather than an empty500",doc("response.md").contains("빈 본문 `500`"));
        assertFalse("HTTP CORS does not configure WebSocket Origin",doc("websocket.md").contains("Express에서 `cors({ origin: [...] })`"));
        assertTrue(doc("websocket.md").contains("HTTP CORS"));
        assertTrue(doc("defaults.md").contains("저사양 Android 기기에서 검증하거나 튜닝한 값은 아닙니다"));
    }
    @Test public void migrationGuideUsesImplementedApiAndCachedBodies() throws Exception {
        String migration=doc("express-migration.md");
        assertFalse("Implemented Router replacement, redirect and CORS are documented",migration.contains("**(미확정)**"));
        // OPS-REL-008 AC-1 AC-3: common Router is the migration path; groups remain a documented extension.
        assertTrue(migration.contains("com.snoworca.naruserve.Router.create"));
        assertTrue(doc("middleware.md").contains("group(prefix, configurer)"));
        assertTrue(migration.contains("app.cors"));
        assertTrue(migration.contains("`redirect`"));
        assertTrue(doc("response.md").contains("res.redirect"));
        assertTrue(migration.contains("attributeAs"));
        assertTrue("Same-type body cache stays documented", migration.contains("같은 타입") && migration.contains("캐시"));
        assertFalse("Same-type body cache is supported",migration.contains("두 번 부르면 `BodyAlreadyConsumedException`"));
    }
}
