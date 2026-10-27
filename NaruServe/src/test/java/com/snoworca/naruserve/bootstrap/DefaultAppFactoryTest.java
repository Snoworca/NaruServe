package com.snoworca.naruserve.bootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.gson.GsonJsonCodec;
import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.spi.AppFactory;
import com.snoworca.naruserve.tinyhttpd.TinyHttpdAdapter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Test;

/** RestApp.create() through the one provider. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-7.sds.md. */
public class DefaultAppFactoryTest {

    private static final String SERVICES = "META-INF/services/com.snoworca.naruserve.spi.AppFactory";

    /** The POJO the echo route decodes. */
    public static final class Named {
        public String name;
    }

    // SDS-AC-4; FR-ENGINE-009 AC-1
    @Test
    public void sdsAc4_FR_ENGINE_009_AC1_serviceLoaderFindsExactlyDefaultAppFactoryAndCreateGivesNewApps()
            throws Exception {
        assertTrue(Modifier.isPublic(DefaultAppFactory.class.getModifiers()));
        assertTrue(Modifier.isPublic(DefaultAppFactory.class.getConstructor().getModifiers()));
        List<AppFactory> found = new ArrayList<>();
        for (AppFactory f : ServiceLoader.load(AppFactory.class, RestApp.class.getClassLoader())) {
            found.add(f);
        }
        assertEquals(1, found.size());
        assertSame(DefaultAppFactory.class, found.get(0).getClass());

        RestApp first = RestApp.create();
        RestApp second = RestApp.create();
        assertNotSame(first, second);
        assertEquals(AppState.NEW, first.state());
        assertEquals(AppState.NEW, second.state());
    }

    // SDS-AC-5; FR-ENGINE-009 AC-2
    @Test(timeout = 20000)
    public void sdsAc5_FR_ENGINE_009_AC2_createdAppEchoesJsonOverASocketThroughTinyHttpdAndGson() throws Exception {
        RestApp app = RestApp.create();
        Field adapter = DefaultRestApp.class.getDeclaredField("adapter");
        adapter.setAccessible(true);
        assertSame(TinyHttpdAdapter.class, adapter.get(app).getClass());
        assertSame(GsonJsonCodec.class, ((DefaultRestApp) app).jsonCodec().getClass());

        app.post("/echo", (req, res) -> res.json(req.body(Named.class)));
        int port = app.listen(0).port();
        try {
            String ok = post(port, "{\"name\":\"a\"}");
            assertEquals(ok, "HTTP/1.1 200", ok.substring(0, 12));
            assertTrue(ok, ok.endsWith("\r\n\r\n{\"name\":\"a\"}"));
            // defaultGson() is strict: the lenient form a plain new Gson() accepts is a 400.
            String lenient = post(port, "{name:'a'}");
            assertEquals(lenient, "HTTP/1.1 400", lenient.substring(0, 12));
        } finally {
            app.shutdown(2, TimeUnit.SECONDS);
        }
    }

    // SDS-AC-7; FR-ENGINE-009 AC-4, FR-ENGINE-009 AC-1
    @Test
    public void sdsAc7_FR_ENGINE_009_AC4_jarHoldsTheConsumerRulesAndTheServicesFile() throws Exception {
        String jar = System.getProperty("naruserve.bootstrapJar");
        assertNotNull("naruserve.bootstrapJar is not set", jar);
        try (ZipFile zip = new ZipFile(new File(jar))) {
            String rules = read(zip, "META-INF/proguard/naruserve-bootstrap.pro");
            assertTrue(rules, rules.contains(
                    "-keep class com.snoworca.naruserve.bootstrap.DefaultAppFactory { public <init>(); public com.snoworca.naruserve.RestApp create(); }"));
            assertTrue(rules, rules.contains("-keepnames class com.snoworca.naruserve.spi.AppFactory"));
            // FR-ENGINE-009 AC-4: both classes are created by name, so R8 sees no instantiation and must keep their methods.
            assertTrue(rules, rules.contains(
                    "-keep class com.snoworca.naruserve.logcat.LogcatLogger { public <init>(); public <methods>; }"));
            assertEquals("com.snoworca.naruserve.bootstrap.DefaultAppFactory", read(zip, SERVICES).trim());
        }
    }

    private static String read(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        assertNotNull(name + " is missing from the jar", e);
        try (InputStream in = zip.getInputStream(e)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) >= 0; ) {
            buf.write(b, 0, n);
        }
        return buf.toByteArray();
    }

    private static String post(int port, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String head = "POST /echo HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n";
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            return new String(readAll(s.getInputStream()), StandardCharsets.UTF_8);
        }
    }
}
