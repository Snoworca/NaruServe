package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The directory listing body. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-8.sds.md. */
public class DirectoryListingTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static void touch(File f) throws IOException {
        new FileOutputStream(f).close();
    }

    // SDS-AC-1; FR-STATIC-003 AC-2
    @Test
    public void sdsAc1_FR_STATIC_003_AC2_entriesSortedRawThenEscapedAndEncoded() throws IOException {
        File dir = tmp.getRoot();
        touch(new File(dir, "b.txt"));
        touch(new File(dir, "a b.txt"));
        touch(new File(dir, "<x>&.txt"));
        touch(new File(dir, ".hidden"));
        assertTrue(new File(dir, "sub").mkdir());
        Files.createSymbolicLink(new File(dir, "link.txt").toPath(), new File(dir, "b.txt").toPath());
        String html = DirectoryListing.render("/files/", dir);

        int x = html.indexOf("href=\"%3Cx%3E%26.txt\">&lt;x&gt;&amp;.txt</a>");
        int ab = html.indexOf("href=\"a%20b.txt\">a b.txt</a>");
        int b = html.indexOf("href=\"b.txt\">b.txt</a>");
        int sub = html.indexOf("href=\"sub/\">sub/</a>");
        assertTrue(html, x > 0 && ab > x && b > ab && sub > b);
        assertFalse(html, html.contains("hidden"));
        assertFalse(html, html.contains("link.txt"));
        assertFalse(html, html.contains("<x>"));
    }
}
