package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import org.junit.Test;

/** Binary compatibility of the 0.11.0 Core changes with code compiled against 0.10.0 (CON-AUTH-007). */
public class CrossModuleAuthCompatTest {

    /** Defines the probe class from bytes compiled against the 0.10.0 sources (see the .java.txt beside them). */
    static final class ProbeLoader extends ClassLoader {
        ProbeLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = CrossModuleAuthCompatTest.class.getResourceAsStream(name)) {
            assertNotNull(name, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    // CON-AUTH-007 AC-2: a class compiled against 0.10.0 calling app.trustProxy(true) runs on 0.11.0 without a
    // LinkageError
    @Test
    public void CON_AUTH_007_AC2_trustProxyStaysBinaryCompatible() throws Exception {
        byte[] probe = resource("compat/TrustProxyProbe-0.10.0.class.bin");
        assertEquals(0xCAFEBABE, ((probe[0] & 0xff) << 24) | ((probe[1] & 0xff) << 16) | ((probe[2] & 0xff) << 8)
                | (probe[3] & 0xff));
        assertEquals(52, probe[7]); // Java 8 class file
        Class<?> c = new ProbeLoader(getClass().getClassLoader()).define("naruprobe.TrustProxyProbe", probe);
        Method run = c.getMethod("run", Object.class);
        TestApp t = TestApp.create();
        Object returned = run.invoke(null, t.app());
        assertSame(t.app(), returned);
        assertTrue(t.app().trustProxy());
    }
}
