package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** The access log {@code in=} field counts the body bytes a handler read through {@code req.bodyStream}. */
public class RawBodyAccessLogTest {

    private static final Pattern IN = Pattern.compile(" in=(\\d+) ");

    /** Drains the reader and decodes any JSON text into a one-entry map {@code text -> the whole text}. */
    static final class DrainingMapCodec implements JsonCodec {
        @Override
        @SuppressWarnings("unchecked")
        public <T> @Nullable T decode(Reader reader, Type type) throws IOException {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[256];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return (T) Collections.singletonMap("text", sb.toString());
        }

        @Override
        public void encode(@Nullable Object value, Writer writer) throws IOException {
            writer.write(String.valueOf(value));
        }
    }

    /** POSTs {@code body} as JSON to a route running {@code handler} and returns the one access log line. */
    private static String accessLine(byte[] body, RouteHandler handler) {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RecordingLogger log = new RecordingLogger();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new DrainingMapCodec()).build();
        app.logger(log);
        app.post("/p", handler);
        app.listen(0);
        ClientResponse resp = adapter.send(ClientRequest.of("POST", "/p").header("Content-Type", "application/json")
                .header("Content-Length", String.valueOf(body.length)).body(body));
        assertEquals(resp.text(), 200, resp.status());
        List<String> info = log.info();
        assertEquals(info.toString(), 1, info.size());
        return info.get(0);
    }

    private static long in(String line) {
        Matcher m = IN.matcher(line);
        assertTrue(line, m.find());
        return Long.parseLong(m.group(1));
    }

    private static long drain(InputStream in) throws IOException {
        long n = 0;
        byte[] buf = new byte[128];
        int r;
        while ((r = in.read(buf)) != -1) {
            n += r;
        }
        return n;
    }

    /** A 1000-byte JSON object: {@code {"a":"xxx...x"}} padded to exactly 1000 bytes. */
    private static byte[] json1000() {
        char[] pad = new char[1000 - "{\"a\":\"\"}".length()];
        Arrays.fill(pad, 'x');
        byte[] body = ("{\"a\":\"" + new String(pad) + "\"}").getBytes(StandardCharsets.UTF_8);
        assertEquals(1000, body.length);
        return body;
    }

    // FR-APP-028 AC-1: reading a 1000-byte body to its end through bodyStream() logs in=1000
    @Test
    public void FR_APP_028_AC1_bodyStreamReadToEndLogsIn1000() {
        String line = accessLine(json1000(), (req, res) -> res.send("read " + drain(req.bodyStream())));
        assertTrue(line, line.startsWith("POST /p 200 "));
        assertEquals(line, 1000, in(line));
    }

    // FR-APP-028 AC-2: reading 4 bytes of a 10-byte body through bodyStream(2048) logs in=4, not the Content-Length
    @Test
    public void FR_APP_028_AC2_partialReadThroughBodyStreamLongLogsBytesReadNotContentLength() {
        byte[] ten = "0123456789".getBytes(StandardCharsets.UTF_8);
        String line = accessLine(ten, (req, res) -> {
            InputStream in = req.bodyStream(2048);
            byte[] four = new byte[4];
            int got = 0;
            while (got < 4) {
                int r = in.read(four, got, 4 - got);
                if (r < 0) {
                    break;
                }
                got += r;
            }
            res.send("read " + got);
        });
        assertTrue(line, line.startsWith("POST /p 200 "));
        assertEquals(line, 4, in(line));
    }

    // FR-APP-028 AC-3: the same 1000-byte JSON body logs the same in= through bodyStream() and req.body(Map.class)
    @Test
    public void FR_APP_028_AC3_bodyStreamAndJsonBodyLogTheSameInForTheSameBody() {
        byte[] body = json1000();
        String raw = accessLine(body, (req, res) -> res.send("read " + drain(req.bodyStream())));
        String json = accessLine(body, (req, res) -> {
            Map<?, ?> value = req.body(Map.class);
            res.send("decoded " + (value == null ? 0 : value.size()));
        });
        assertEquals(json, 1000, in(json));
        assertEquals(raw + " vs " + json, in(json), in(raw));
    }
}
