package com.snoworca.naruserve;

import static com.snoworca.naruserve.StaticTraversalTest.write;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** T28 MIME lookup over the in-memory adapter. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-7.sds.md. */
public class MimeTypesTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private File root() throws IOException {
        File root = tmp.getRoot();
        for (String n : new String[] {"a.foo", "a.js", "a.unknownext", "a.css", "a.html", "a.png", "a.cust",
                "a.latin", "a.img", "x.svg", "APP.JS", "f.data", "f.webp"}) {
            write(new File(root, n), n);
        }
        return root;
    }

    private static String type(TestApp t, String path) {
        return t.send("GET", path).header("Content-Type");
    }

    // SDS-AC-8; FR-STATIC-007 AC-4
    @Test
    public void sdsAc8_FR_STATIC_007_AC4_onlyBareTextTypesGainUtf8Charset() throws IOException {
        TestApp t = TestApp.create();
        t.app().mimeTypes().register("cust", "text/x-custom").register("latin", "text/plain; charset=ISO-8859-1")
                .register("img", "image/png");
        t.app().staticFiles("/", root());
        assertEquals("text/x-custom; charset=UTF-8", type(t, "/a.cust"));
        assertEquals("text/plain; charset=ISO-8859-1", type(t, "/a.latin"));
        assertEquals("image/png", type(t, "/a.img"));
        assertEquals("text/css; charset=UTF-8", type(t, "/a.css"));
        assertEquals("text/html; charset=UTF-8", type(t, "/a.html"));
        assertEquals("image/png", type(t, "/a.png"));
    }

    // FR-STATIC-007 AC-1 (T28)
    @Test
    public void FR_STATIC_007_AC1_mountThenAppThenBuiltInThenOctetStream() throws IOException {
        File root = root();
        TestApp both = TestApp.create();
        both.app().mimeTypes().register("foo", "application/x-app");
        both.app().staticFiles("/", root, o -> o.mime("foo", "application/x-mount"));
        assertEquals("application/x-mount", type(both, "/a.foo"));
        TestApp app = TestApp.create();
        app.app().mimeTypes().register("foo", "application/x-app");
        app.app().staticFiles("/", root);
        assertEquals("application/x-app", type(app, "/a.foo"));
        assertEquals("text/javascript; charset=UTF-8", type(app, "/a.js"));
        assertEquals("application/octet-stream", type(app, "/a.unknownext"));
    }

    // FR-STATIC-007 AC-3
    @Test
    public void FR_STATIC_007_AC3_extensionsMatchWithoutRegardToCaseOrLeadingDot() throws IOException {
        TestApp t = TestApp.create();
        t.app().mimeTypes().register(".webp", "image/x-webp").register("Svg", "image/x-svg");
        t.app().staticFiles("/", root(), o -> o.mime(".data", "application/x-data"));
        assertEquals("text/javascript; charset=UTF-8", type(t, "/APP.JS"));
        assertEquals("image/x-svg", type(t, "/x.svg"));
        assertEquals("application/x-data", type(t, "/f.data"));
        assertEquals("image/x-webp", type(t, "/f.webp"));
        TestApp m = TestApp.create();
        m.app().staticFiles("/", root(), o -> o.mime("SVG", "image/x-mount-svg").mime("data", "application/x-d"));
        assertEquals("image/x-mount-svg", type(m, "/x.svg"));
        assertEquals("application/x-d", type(m, "/f.data"));
    }

    // FR-STATIC-007 AC-5 (T28)
    @Test
    public void FR_STATIC_007_AC5_registrationChainsAndStaysInItsApp() throws IOException {
        File root = root();
        TestApp one = TestApp.create();
        MimeTypes m = one.app().mimeTypes();
        assertSame(m, m.register("webp", "image/x-one").register("avif", "image/avif"));
        one.app().staticFiles("/", root);
        TestApp two = TestApp.create();
        two.app().staticFiles("/", root);
        assertEquals("image/x-one", type(one, "/f.webp"));
        assertEquals("image/webp", type(two, "/f.webp"));
    }
}
