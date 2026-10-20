package com.snoworca.naruserve.docs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;
import static org.junit.Assert.*;

/** OPS-DX-002 AC-1..4: examples must not contradict implemented behavior. */
public class UsabilityDocsTest {
    private String doc(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("docs/api", name)), StandardCharsets.UTF_8);
    }
    @Test public void errorAndOriginGuidesDescribeSeparateMechanisms() throws Exception {
        assertFalse("Unhandled errors have standard JSON",doc("response.md").contains("빈 본문 `500`"));
        assertFalse("HTTP CORS does not configure WebSocket Origin",doc("websocket.md").contains("Express에서 `cors({ origin: [...] })`"));
        assertTrue(doc("websocket.md").contains("HTTP CORS"));
        assertTrue(doc("defaults.md").contains("저사양 Android 기기에서 검증하거나 튜닝한 값은 아닙니다"));
    }
    @Test public void migrationGuideUsesImplementedApiAndCachedBodies() throws Exception {
        String migration=doc("express-migration.md");
        assertFalse("Implemented Router replacement, redirect and CORS are documented",migration.contains("**(미확정)**"));
        assertTrue(migration.contains("app.group"));
        assertTrue(migration.contains("app.cors"));
        assertTrue(migration.contains("res.redirect"));
        assertTrue(migration.contains("attributeAs"));
        assertFalse("Same-type body cache is supported",migration.contains("두 번 부르면 `BodyAlreadyConsumedException`"));
    }
}
