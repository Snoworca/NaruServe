package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** The built-in MIME table and lookup. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-7.sds.md. */
public class DefaultMimeTypesTest {

    private static final Map<String, String> NONE = Collections.emptyMap();

    // SDS-AC-7; FR-STATIC-007 AC-1; FR-STATIC-007 AC-3
    @Test
    public void sdsAc7_FR_STATIC_007_AC1_extensionIsTextAfterLastDotCaseInsensitive() {
        DefaultMimeTypes m = new DefaultMimeTypes(new AppLifecycle());
        assertEquals("application/octet-stream", m.lookup("Makefile", NONE));
        assertEquals("application/octet-stream", m.lookup("a.", NONE));
        assertEquals("application/octet-stream", m.lookup("a.unknownext", NONE));
        assertEquals("text/javascript; charset=UTF-8", m.lookup("APP.JS", NONE));
        assertEquals("text/javascript; charset=UTF-8", m.lookup("app.Js", NONE));
        assertEquals("application/json", m.lookup("x.tar.map", NONE));
    }

    // FR-STATIC-007 AC-1; FR-STATIC-007 AC-2
    @Test
    public void FR_STATIC_007_AC1_commonTypesAreBuiltIn() {
        DefaultMimeTypes m = new DefaultMimeTypes(new AppLifecycle());
        assertEquals("text/plain; charset=UTF-8", m.lookup("robots.txt", NONE));
        assertEquals("text/csv; charset=UTF-8", m.lookup("a.CSV", NONE));
        assertEquals("image/gif", m.lookup("a.gif", NONE));
        assertEquals("image/x-icon", m.lookup("favicon.ico", NONE));
        assertEquals("application/xml", m.lookup("sitemap.xml", NONE));
        assertEquals("audio/mpeg", m.lookup("a.mp3", NONE));
        assertEquals("audio/ogg", m.lookup("a.ogg", NONE));
    }

    // FR-STATIC-007 AC-2; CON-STATIC-001 AC-1
    @Test
    public void FR_STATIC_007_AC2_builtInTableIsExactlyTheDocsTable() throws IOException {
        File doc = new File(System.getProperty("user.dir"), "../docs/api/static.md");
        String md = new String(Files.readAllBytes(doc.toPath()), StandardCharsets.UTF_8);
        String table = md.substring(md.indexOf("### 내장 표"), md.indexOf("## 캐시 정책"));
        Map<String, String> expected = new LinkedHashMap<>();
        Matcher row = Pattern.compile("(?m)^\\| (`\\.[^|]+) \\| `([^`]+)` \\|$").matcher(table);
        while (row.find()) {
            Matcher ext = Pattern.compile("`\\.([^`]+)`").matcher(row.group(1));
            while (ext.find()) {
                expected.put(ext.group(1), row.group(2));
            }
        }
        assertEquals("text/html", expected.get("htm")); // guards the parser: a two-extension row parsed
        assertEquals("application/pdf", expected.get("pdf"));
        assertEquals(expected, DefaultMimeTypes.builtIn());
    }

    // FR-STATIC-007 AC-5
    @Test
    public void FR_STATIC_007_AC5_builtInIsUnmodifiableAndRegistrationLeavesItAlone() {
        DefaultMimeTypes m = new DefaultMimeTypes(new AppLifecycle());
        m.register("png", "image/x-png");
        assertEquals("image/png", DefaultMimeTypes.builtIn().get("png"));
        assertEquals("image/x-png", m.lookup("a.png", NONE));
        try {
            DefaultMimeTypes.builtIn().put("x", "y");
            fail("BUILT_IN modified");
        } catch (UnsupportedOperationException expected) {
            // unmodifiable
        }
    }

    // IR-STATIC-002: a MIME type that is not a valid header field value is refused at setup, in both registries
    @Test
    public void IR_STATIC_002_typeWithControlCharacterIsRefusedAtSetup() {
        DefaultMimeTypes m = new DefaultMimeTypes(new AppLifecycle());
        DefaultStaticOptions o = new DefaultStaticOptions();
        for (String bad : new String[] {"text/plain\r\nX-Evil: 1", "text/plain\n", "text/\u0000plain", "text/\u010Aplain"}) {
            try {
                m.register(".x", bad);
                fail("register accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
            try {
                o.mime(".x", bad);
                fail("mime accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
        m.register(".x", "text/plain;\tcharset=UTF-8");
        assertEquals("text/plain;\tcharset=UTF-8", m.lookup("a.x", NONE));
    }
}
