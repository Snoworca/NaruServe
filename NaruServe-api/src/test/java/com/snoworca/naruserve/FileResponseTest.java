package com.snoworca.naruserve;

import static com.snoworca.naruserve.StaticTraversalTest.write;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** res.file. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-7.sds.md. */
public class FileResponseTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static String text(ClientResponse r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    // SDS-AC-4; FR-STATIC-006 AC-5
    @Test
    public void sdsAc4_FR_STATIC_006_AC5_resFileOnLinkDirectoryOrMissingIs404StandardBody() throws IOException {
        File base = tmp.getRoot();
        File real = new File(base, "real.html");
        write(real, "REAL");
        File link = new File(base, "link.txt");
        Files.createSymbolicLink(link.toPath(), real.toPath());
        File dir = new File(base, "dir");
        assertTrue(dir.mkdir());
        File missing = new File(base, "missing.txt");
        TestApp t = TestApp.create();
        t.app().get("/real", (req, res) -> res.file(real));
        t.app().get("/link", (req, res) -> res.file(link));
        t.app().get("/dir", (req, res) -> res.file(dir));
        t.app().get("/missing", (req, res) -> res.file(missing));
        ClientResponse ok = t.send("GET", "/real");
        assertEquals(200, ok.status());
        assertEquals("REAL", text(ok));
        assertEquals("text/html; charset=UTF-8", ok.header("Content-Type"));
        for (String p : new String[] {"/link", "/dir", "/missing"}) {
            ClientResponse r = t.send("GET", p);
            assertEquals(p, 404, r.status());
            assertTrue(p + ": " + text(r), text(r).startsWith("{\"error\":{\"code\":\"NOT_FOUND\""));
        }
    }
}
