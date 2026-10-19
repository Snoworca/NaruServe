package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
}
