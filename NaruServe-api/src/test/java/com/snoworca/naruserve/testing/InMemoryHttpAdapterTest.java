package com.snoworca.naruserve.testing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.HttpExchange;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class InMemoryHttpAdapterTest {

    /** Records the raw request line of every exchange and answers 204. */
    private static final class Recorder {
        final List<String[]> seen = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();

        Recorder() throws IOException {
            adapter.bind("127.0.0.1", 0, this::handle);
        }

        private void handle(HttpExchange exchange) {
            calls.incrementAndGet();
            seen.add(new String[] {exchange.methodToken(), exchange.rawPath(), exchange.rawQuery()});
            try {
                exchange.commit(204, Collections.<Map.Entry<String, String>>emptyList(), 0);
                exchange.finish();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        String[] send(String method, String target) {
            ClientResponse r = adapter.send(ClientRequest.of(method, target));
            assertEquals(204, r.status());
            return seen.get(seen.size() - 1);
        }
    }

    // SDS-AC-3; IR-HTTP-003 AC-3, FR-HTTP-001 AC-4
    @Test
    public void sdsAc3_IR_HTTP_003_AC3_FR_HTTP_001_AC4_rawPathAndRawQueryTravelUndecoded() throws IOException {
        Recorder r = new Recorder();
        assertArrayEquals(new String[] {"GET", "/a%20b", "x=%41"}, r.send("GET", "/a%20b?x=%41"));
        assertArrayEquals(new String[] {"GET", "/a%20b", "q=%20"}, r.send("GET", "/a%20b?q=%20"));
        assertArrayEquals(new String[] {"GET", "/plain", null}, r.send("GET", "/plain"));
        assertArrayEquals(new String[] {"GET", "/e", ""}, r.send("GET", "/e?"));
        assertArrayEquals(new String[] {"/a%20b", "x=%41"}, InMemoryHttpAdapter.splitTarget("/a%20b?x=%41"));
        assertNull(InMemoryHttpAdapter.splitTarget("/plain")[1]);
    }

    // SDS-AC-8; FR-HTTP-001 AC-1
    @Test
    public void sdsAc8_FR_HTTP_001_AC1_adapterIsAFixturesClassImplementingHttpAdapter() {
        assertTrue(HttpAdapter.class.isAssignableFrom(InMemoryHttpAdapter.class));
        String location = InMemoryHttpAdapter.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        assertTrue(location, location.contains("testFixtures") || location.contains("test-fixtures"));
    }

    // SDS-AC-9; FR-HTTP-001 AC-5
    @Test
    public void sdsAc9_FR_HTTP_001_AC5_authorityFormConnectGets501WithoutCallingTheHandler() throws IOException {
        Recorder r = new Recorder();
        ClientResponse response = r.adapter.send(ClientRequest.of("CONNECT", "host:443"));
        assertEquals(501, response.status());
        assertEquals("0", response.header("Content-Length"));
        assertEquals(0, response.body().length);
        assertFalse(response.abnormallyClosed());
        assertEquals(0, r.calls.get());
    }

    // SDS-AC-10; FR-HTTP-001 AC-6, FR-HTTP-001 AC-7
    @Test
    public void sdsAc10_FR_HTTP_001_AC6_AC7_absoluteFormIsRewrittenAndAsteriskPassesAsIs() throws IOException {
        Recorder r = new Recorder();
        assertArrayEquals(new String[] {"GET", "/a", "x=1"}, r.send("GET", "http://example.com/a?x=1"));
        assertArrayEquals(new String[] {"GET", "/", null}, r.send("GET", "https://example.com"));
        assertArrayEquals(new String[] {"GET", "/", "y=2"}, r.send("GET", "http://example.com:8080?y=2"));
        assertArrayEquals(new String[] {"OPTIONS", "*", null}, r.send("OPTIONS", "*"));
        assertArrayEquals(new String[] {"/a", "x=1"}, InMemoryHttpAdapter.splitTarget("http://example.com/a?x=1"));
        assertArrayEquals(new String[] {"*", null}, InMemoryHttpAdapter.splitTarget("*"));
    }
}
