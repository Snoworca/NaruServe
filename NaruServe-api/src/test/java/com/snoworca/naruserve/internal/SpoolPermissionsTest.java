package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** A spooled upload temp file is readable and writable by its owner only (FR-MULTIPART-004). */
public class SpoolPermissionsTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void tempFileIsOwnerOnly() throws Exception {
        assumeTrue(Files.getFileStore(tmp.getRoot().toPath()).supportsFileAttributeView("posix"));
        File f = new SpoolRegistry().createTemp(tmp.getRoot());
        Set<PosixFilePermission> p = Files.getPosixFilePermissions(f.toPath());
        p.removeAll(java.util.EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        assertEquals(Collections.<PosixFilePermission>emptySet(), p);
    }
}
