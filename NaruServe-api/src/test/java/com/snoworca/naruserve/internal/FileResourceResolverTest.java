package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.ResourceResolver;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The built-in java.io.File resolver. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-5.sds.md. */
public class FileResourceResolverTest {

    private interface Call {
        void run() throws Exception;
    }

    // SDS-AC-4; IR-STATIC-001 AC-3
    @Test
    public void sdsAc4_IR_STATIC_001_AC3_missingFileThrowsFileNotFoundExceptionFromEveryMethod() throws Exception {
        File root = new File("build").getCanonicalFile();
        FileResourceResolver r = new FileResourceResolver(root, false);
        String missing = "no-such-file-" + System.nanoTime();
        Call[] calls = {() -> r.open(missing).close(), () -> r.length(missing), () -> r.etag(missing),
                () -> r.modified(missing)};
        for (Call c : calls) {
            try {
                c.run();
                fail("missing path read");
            } catch (FileNotFoundException expected) {
                // refused
            }
        }
    }

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    // SDS-AC-5 (wave-6-7 SDS); FR-STATIC-006 AC-1
    @Test
    public void sdsAc5_FR_STATIC_006_AC1_siblingRootSharingThePrefixIsNotUnderTheRoot() throws Exception {
        File base = tmp.getRoot().getCanonicalFile();
        File root = new File(base, "root");
        assertTrue(root.mkdir());
        File root2 = new File(base, "root2");
        assertTrue(root2.mkdir());
        try (OutputStream out = new FileOutputStream(new File(root2, "x.txt"))) {
            out.write('x');
        }
        // allowSymlinks(true) skips the component check, so only containment can refuse
        FileResourceResolver r = new FileResourceResolver(root, true);
        try {
            r.open("../root2/x.txt").close();
            fail("sibling root2 served");
        } catch (FileNotFoundException expected) {
            // refused
        }
    }

    // SDS-AC-5 (wave-6-7 SDS); FR-STATIC-006 AC-1
    @Test
    public void sdsAc5_FR_STATIC_006_AC1_rootReachedThroughLinkServesItsFilesButNotLinksInside() throws Exception {
        File base = tmp.getRoot();
        File real = new File(base, "realRoot");
        assertTrue(real.mkdir());
        try (OutputStream out = new FileOutputStream(new File(real, "a.txt"))) {
            out.write('a');
        }
        Files.createSymbolicLink(new File(real, "b.txt").toPath(), new File(real, "a.txt").toPath());
        File link = new File(base, "link");
        Files.createSymbolicLink(link.toPath(), real.toPath());
        FileResourceResolver r = new FileResourceResolver(link.getCanonicalFile(), false);
        try (InputStream in = r.open("a.txt")) {
            assertEquals('a', in.read());
        }
        assertEquals(1, r.length("a.txt"));
        try {
            r.open("b.txt").close();
            fail("link inside served");
        } catch (FileNotFoundException expected) {
            // refused
        }
    }

    /** A real resolver that records each call with its delegate's class, then delegates. */
    private static final class Recording implements ResourceResolver {
        private final ResourceResolver delegate;
        private final List<String> calls;

        Recording(ResourceResolver delegate, List<String> calls) {
            this.delegate = delegate;
            this.calls = calls;
        }

        private void see(String call) {
            synchronized (calls) {
                calls.add(call + ":" + delegate.getClass().getSimpleName());
            }
        }

        @Override
        public InputStream open(String path) throws IOException {
            see("open");
            return delegate.open(path);
        }

        @Override
        public long length(String path) throws IOException {
            see("length");
            return delegate.length(path);
        }

        @Override
        public String etag(String path) throws IOException {
            see("etag");
            return delegate.etag(path);
        }

        @Override
        public long modified(String path) throws IOException {
            see("modified");
            return delegate.modified(path);
        }
    }

    // FR-STATIC-011 AC-5: static mounts, res.file and res.download all read through the built-in java.io.File
    // resolver, observed as its open, length, etag and modified calls for each of the three
    @Test
    public void FR_STATIC_011_AC5_staticFileAndDownloadReadThroughTheBuiltInResolver() throws Exception {
        File root = tmp.newFolder("served");
        try (OutputStream out = new FileOutputStream(new File(root, "a.txt"))) {
            out.write("hello".getBytes(StandardCharsets.UTF_8));
        }
        TestApp t = TestApp.create();
        t.app().staticFiles("/s", root);
        t.app().get("/f", (req, res) -> res.file(new File(root, "a.txt")));
        t.app().get("/d", (req, res) -> res.download(new File(root, "a.txt"), "a.txt"));
        assertEquals(200, t.send("GET", "/s/a.txt").status()); // listens
        Field f = Dispatcher.class.getDeclaredField("statics");
        f.setAccessible(true);
        StaticHandler statics = (StaticHandler) f.get(((DefaultRestApp) t.app()).dispatcher());
        List<String> calls = new ArrayList<>();
        statics.resolverTap(r -> new Recording(r, calls));
        List<String> expected = Arrays.asList("length:FileResourceResolver", "etag:FileResourceResolver",
                "modified:FileResourceResolver", "open:FileResourceResolver");
        for (String path : new String[] {"/s/a.txt", "/f", "/d"}) {
            synchronized (calls) {
                calls.clear();
            }
            ClientResponse r = t.send("GET", path);
            assertEquals(path, 200, r.status());
            assertEquals(path, "hello", new String(r.body(), StandardCharsets.UTF_8));
            synchronized (calls) {
                assertEquals(path, expected, calls);
            }
        }
    }
}
