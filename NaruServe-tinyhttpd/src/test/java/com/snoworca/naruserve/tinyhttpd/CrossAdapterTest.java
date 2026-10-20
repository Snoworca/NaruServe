package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.NoLeftoverThreads;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

/** T07 and the path cases on the in-memory and TinyHttpd adapters. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-7.sds.md. */
public class CrossAdapterTest {

    // I-015: no naruserve-* thread outlives the test
    @Rule
    public final NoLeftoverThreads noLeftoverThreads = new NoLeftoverThreads();

    /** The in-memory adapter of the NaruServe-api test fixtures. */
    static final class InMemoryRun extends BothAdaptersCases {
        private final Map<RestApp, TestApp> apps = new IdentityHashMap<>();

        @Override
        RestApp newApp() {
            TestApp t = TestApp.create();
            apps.put(t.app(), t);
            return t.app();
        }

        @Override
        Reply send(RestApp app, String method, String target, String... headerPairs) {
            ClientResponse r = apps.get(app).send(method, target, headerPairs);
            String body = new String(r.body(), StandardCharsets.UTF_8);
            return reply(r.status(), r::header, body);
        }

        @Override
        void close() {
            for (RestApp app : apps.keySet()) {
                app.shutdown(1, TimeUnit.SECONDS);
            }
        }
    }

    /** TinyHttpd on listen(0) with a raw socket client that sends Host. */
    static final class SocketRun extends BothAdaptersCases {
        private final Map<RestApp, Integer> ports = new IdentityHashMap<>();

        @Override
        RestApp newApp() {
            return RestApp.builder().httpAdapter(new TinyHttpdAdapter()).jsonCodec(new StubJsonCodec())
                    .logger(new RecordingLogger()).build();
        }

        @Override
        Reply send(RestApp app, String method, String target, String... headerPairs) throws Exception {
            Integer port = ports.get(app);
            if (port == null) {
                port = app.listen(0).port();
                ports.put(app, port);
            }
            StringBuilder raw = new StringBuilder(method).append(' ').append(target)
                    .append(" HTTP/1.1\r\nHost: 127.0.0.1\r\n");
            for (int i = 0; i < headerPairs.length; i += 2) {
                raw.append(headerPairs[i]).append(": ").append(headerPairs[i + 1]).append("\r\n");
            }
            raw.append("Connection: close\r\n\r\n");
            // The target goes out as ISO-8859-1, so a char below 0x100 is that raw byte.
            String response = RawTargetSocketTest.Http.send(port, raw.toString());
            int status = RawTargetSocketTest.Http.status(response);
            int end = response.indexOf("\r\n\r\n");
            List<String[]> headers = new ArrayList<>();
            for (String line : response.substring(0, end).split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.add(new String[] {line.substring(0, colon).trim(), line.substring(colon + 1).trim()});
                }
            }
            Header lookup = name -> {
                for (String[] h : headers) {
                    if (h[0].equalsIgnoreCase(name)) {
                        return h[1];
                    }
                }
                return null;
            };
            byte[] bytes = response.substring(end + 4).getBytes(StandardCharsets.ISO_8859_1);
            String te = lookup.get("Transfer-Encoding");
            if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
                bytes = dechunk(bytes);
            }
            return reply(status, lookup, new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        void close() {
            for (RestApp app : ports.keySet()) {
                app.shutdown(1, TimeUnit.SECONDS);
            }
        }

        private static byte[] dechunk(byte[] in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int i = 0;
            while (i < in.length) {
                int lineEnd = i;
                while (in[lineEnd] != '\r') {
                    lineEnd++;
                }
                String sizeField = new String(in, i, lineEnd - i, StandardCharsets.ISO_8859_1);
                int size = Integer.parseInt(sizeField.split(";")[0].trim(), 16);
                if (size == 0) {
                    break;
                }
                out.write(in, lineEnd + 2, size);
                i = lineEnd + 2 + size + 2;
            }
            return out.toByteArray();
        }
    }

    interface Header {
        String get(String name);
    }

    static BothAdaptersCases.Reply reply(int status, Header header, String body) {
        return new BothAdaptersCases.Reply() {
            @Override
            public int status() {
                return status;
            }

            @Override
            public String header(String name) {
                return header.get(name);
            }

            @Override
            public String body() {
                return body;
            }
        };
    }

    interface Cases {
        void run(BothAdaptersCases c) throws Exception;
    }

    private static void onBoth(Cases cases) throws Exception {
        List<List<String>> transcripts = new ArrayList<>();
        for (BothAdaptersCases run : new BothAdaptersCases[] {new InMemoryRun(), new SocketRun()}) {
            try {
                cases.run(run);
            } finally {
                run.close();
            }
            assertFalse(run.transcript.isEmpty());
            transcripts.add(run.transcript);
        }
        assertEquals("in-memory vs TinyHttpd", transcripts.get(0), transcripts.get(1));
    }

    // SDS-AC-1; FR-ENGINE-007 AC-1
    @Test(timeout = 30000)
    public void sdsAc1_FR_ENGINE_007_AC1_t07RunsOnBothAdaptersWithTheSameResults() throws Exception {
        onBoth(BothAdaptersCases::t07Cases);
    }

    // SDS-AC-2; FR-ENGINE-007 AC-2
    @Test(timeout = 30000)
    public void sdsAc2_FR_ENGINE_007_AC2_pathRejectionsAndOptionsStarGiveTheSameStatusOnBoth() throws Exception {
        onBoth(BothAdaptersCases::pathRejectionCases);
    }

    // SDS-AC-3; FR-ENGINE-007 AC-3
    @Test(timeout = 30000)
    public void sdsAc3_FR_ENGINE_007_AC3_handlerReachingPathsGiveTheSameRawPathAndDecodedValuesOnBoth()
            throws Exception {
        onBoth(BothAdaptersCases::handlerReachingCases);
    }

    // FR-ENGINE-008 AC-4
    @Test(timeout = 30000)
    public void FR_ENGINE_008_AC4_proxyTrustGivesTheSameRemoteAddressAndIsSecureOnBoth() throws Exception {
        onBoth(BothAdaptersCases::proxyTrustCases);
    }

    // FR-ENGINE-008 AC-4: the trust decision lives in DefaultRequest, so no adapter class names a forwarded header.
    @Test
    public void FR_ENGINE_008_AC4_noAdapterClassReadsForwardedHeaders() throws Exception {
        Map<String, String> classes = new java.util.TreeMap<>();
        for (java.io.File f : AdapterSurfaceTest.mainClassFiles()) {
            classes.put(f.getName(), AdapterSurfaceTest.classText(f));
        }
        assertFalse(classes.isEmpty());
        // The in-memory adapter comes from the NaruServe-api test-fixtures jar.
        java.net.URL url = com.snoworca.naruserve.testing.InMemoryHttpAdapter.class.getResource("InMemoryHttpAdapter.class");
        int inMemory = 0;
        try (java.util.jar.JarFile jar = ((java.net.JarURLConnection) url.openConnection()).getJarFile()) {
            for (java.util.jar.JarEntry e : java.util.Collections.list(jar.entries())) {
                if (e.getName().startsWith("com/snoworca/naruserve/testing/InMemoryHttpAdapter")
                        && e.getName().endsWith(".class")) {
                    try (java.io.InputStream in = jar.getInputStream(e)) {
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        byte[] b = new byte[8192];
                        for (int n; (n = in.read(b)) >= 0; ) {
                            buf.write(b, 0, n);
                        }
                        classes.put(e.getName(), new String(buf.toByteArray(), StandardCharsets.ISO_8859_1));
                        inMemory++;
                    }
                }
            }
        }
        assertTrue(inMemory > 0);
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, String> c : classes.entrySet()) {
            if (c.getValue().toLowerCase(Locale.ROOT).contains("forwarded")) {
                hits.add(c.getKey());
            }
        }
        assertEquals(new ArrayList<String>(), hits);
    }
}
