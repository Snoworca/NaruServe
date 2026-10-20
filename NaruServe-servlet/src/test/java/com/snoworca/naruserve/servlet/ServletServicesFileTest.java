package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Test;

/** Reads the services file from the built module jar, as DefaultAppFactoryTest reads the bootstrap one. */
public class ServletServicesFileTest {

    private static final String SERVICES = "META-INF/services/javax.servlet.ServletContainerInitializer";

    // SDS-AC-2; IR-SVL-001 AC-2
    @Test
    public void sdsAc2_IR_SVL_001_AC2_jarServicesFileNamesOnlyTheInitializer() throws IOException {
        String jar = System.getProperty("naruserve.servletJar");
        assertNotNull("naruserve.servletJar is not set", jar);
        try (ZipFile zip = new ZipFile(new File(jar))) {
            ZipEntry e = zip.getEntry(SERVICES);
            assertNotNull(SERVICES + " is missing from the jar", e);
            String text;
            try (InputStream in = zip.getInputStream(e)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            assertEquals("com.snoworca.naruserve.servlet.NaruServeInitializer", text.trim());
        }
    }
}
