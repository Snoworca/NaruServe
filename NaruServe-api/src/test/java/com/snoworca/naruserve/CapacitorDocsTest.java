package com.snoworca.naruserve;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

/** The Capacitor and WebView section of both middleware guides (OPS-DX-004). */
public class CapacitorDocsTest {

    private static String lastSection(String doc) {
        return doc.substring(doc.lastIndexOf("\n## "));
    }

    // OPS-DX-004 AC-1: the last section of both guides names the origins, how they change, the matching rules and
    // the Express counterpart
    @Test
    public void OPS_DX_004_AC1_bothGuidesEndWithTheCapacitorSection() throws IOException {
        for (String name : new String[] {"middleware.md", "en/middleware.md"}) {
            String section = lastSection(HttpDocsTest.doc(name));
            assertTrue(name, section.startsWith("\n## Capacitor"));
            for (String phrase : new String[] {"`capacitor://localhost`", "`https://localhost`", "`server.iosScheme`",
                    "`server.androidScheme`", "`server.hostname`", "`capacitor.config`", "cors({ origin: [",
                    "app.cors(", "app.webSocket("}) {
                assertTrue(name + " names " + phrase, section.contains(phrase));
            }
        }
        String ko = lastSection(HttpDocsTest.doc("middleware.md"));
        assertTrue(ko.contains("기본 포트가 없") && ko.contains("same-origin"));
        String en = lastSection(HttpDocsTest.doc("en/middleware.md"));
        assertTrue(en.contains("no default port") && en.contains("never same-origin"));
    }

    // OPS-DX-004 AC-2: the section holds one Java and one Kotlin block, the file's last ones
    @Test
    public void OPS_DX_004_AC2_theSectionHoldsTheLastJavaAndKotlinBlocks() throws IOException {
        for (String name : new String[] {"middleware.md", "en/middleware.md"}) {
            String section = lastSection(HttpDocsTest.doc(name));
            assertTrue(name, section.split("```java", -1).length == 2);
            assertTrue(name, section.split("```kotlin", -1).length == 2);
            assertTrue(name, section.contains("allowedOrigins(\"capacitor://localhost\", \"https://localhost\")"));
        }
    }
}
