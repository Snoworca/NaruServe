package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** T29 traversal and dot segments over the in-memory adapter. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-6.sds.md. */
public class StaticTraversalTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private TestApp app() throws IOException {
        File base = tmp.getRoot();
        write(new File(base, "secret.txt"), "SECRET");
        File root = new File(base, "root");
        write(new File(root, "index.html"), "INDEX");
        write(new File(root, ".env"), "ENV");
        write(new File(root, ".git/config"), "GIT");
        write(new File(root, "assets/.hidden/x.js"), "HIDDEN");
        write(new File(root, "assets/app.js"), "APP");
        TestApp t = TestApp.create();
        t.app().staticFiles("/", root);
        return t;
    }

    // SDS-AC-1; FR-STATIC-005 AC-1 (T29)
    @Test
    public void sdsAc1_FR_STATIC_005_AC1_traversalRequestsAre400AndSecretNeverSent() throws IOException {
        TestApp t = app();
        for (String path : new String[] {"/../secret.txt", "/%2e%2e/secret.txt", "/a/%2E%2E/%2E%2E/secret.txt",
                "/a%2Fb", "/a%2fb", "/a%00.txt", "/%252e%252e/secret.txt", "/a%252Fb"}) {
            ClientResponse r = t.send("GET", path, "Accept", "text/html");
            assertEquals(path, 400, r.status());
            assertFalse(path, new String(r.body(), StandardCharsets.UTF_8).contains("SECRET"));
        }
    }

    // SDS-AC-2; FR-STATIC-005 AC-2; FR-STATIC-001 AC-6 (T29)
    @Test
    public void sdsAc2_FR_STATIC_005_AC2_dotSegments400FirstHiddenFiles404EmptySegment404() throws IOException {
        TestApp t = app();
        assertEquals(400, t.send("GET", "/./index.html").status());
        assertEquals(400, t.send("GET", "/a/../index.html").status());
        for (String path : new String[] {"/.git/config", "/.env", "/assets/.hidden/x.js", "//assets"}) {
            ClientResponse r = t.send("GET", path);
            assertEquals(path, 404, r.status());
            assertNull(path, r.header("Location"));
        }
        assertEquals("APP", new String(t.send("GET", "/assets/app.js").body(), StandardCharsets.UTF_8));
    }

    // FR-STATIC-005 AC-1: a decoded backslash is refused like '/' on every platform (a Windows separator)
    @Test
    public void FR_STATIC_005_AC1_decodedBackslashIs400() throws IOException {
        TestApp t = app();
        for (String path : new String[] {"/a%5C..%5C.env", "/a%5cb.txt", "/assets%5Capp.js", "/%5C"}) {
            for (String m : new String[] {"GET", "POST"}) {
                ClientResponse r = t.send(m, path);
                assertEquals(m + " " + path, 400, r.status());
                assertFalse(path, new String(r.body(), StandardCharsets.UTF_8).contains("ENV"));
            }
        }
    }
}
