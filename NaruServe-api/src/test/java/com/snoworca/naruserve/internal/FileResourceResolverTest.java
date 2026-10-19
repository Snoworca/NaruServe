package com.snoworca.naruserve.internal;

import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileNotFoundException;
import org.junit.Test;

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
}
