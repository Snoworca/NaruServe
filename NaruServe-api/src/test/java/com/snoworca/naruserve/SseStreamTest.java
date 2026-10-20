package com.snoworca.naruserve;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.internal.DefaultResponse;
import com.snoworca.naruserve.internal.ResponseState;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** res.sse() over the in-memory adapter. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-9.sds.md. */
public class SseStreamTest {

    private final TestApp t = TestApp.create();

    private static int count(ClientResponse r, String name) {
        int n = 0;
        for (Map.Entry<String, String> h : r.headers()) {
            if (h.getKey().equalsIgnoreCase(name)) {
                n++;
            }
        }
        return n;
    }

    private static String text(ClientResponse r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    /**
     * The event dispatch of the WHATWG "Server-sent events" parsing rules, reduced to what these tests send: each
     * event as "type|data", where an event without an event field is dispatched as "message".
     */
    private static List<String> parse(String stream) {
        List<String> events = new ArrayList<>();
        String type = "";
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        for (String line : stream.split("\n", -1)) {
            if (line.isEmpty()) {
                if (hasData) {
                    String d = data.toString();
                    events.add((type.isEmpty() ? "message" : type) + "|" + d.substring(0, d.length() - 1));
                }
                type = "";
                data.setLength(0);
                hasData = false;
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int c = line.indexOf(':');
            String field = c < 0 ? line : line.substring(0, c);
            String value = c < 0 ? "" : line.substring(c + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if (field.equals("event")) {
                type = value;
            } else if (field.equals("data")) {
                data.append(value).append('\n');
                hasData = true;
            }
        }
        return events;
    }

    // SDS-AC-6; FR-SSE-001 AC-1, AC-3
    @Test
    public void sdsAc6_FR_SSE_001_AC1_AC3_sseCommits200WithEachEventStreamHeaderOnceAndEntersStreaming() {
        AtomicReference<String> seen = new AtomicReference<>();
        t.app().get("/events", (req, res) -> {
            res.status(201).header("Content-Type", "text/plain").header("Cache-Control", "max-age=60")
                    .header("Content-Length", "5").header("Transfer-Encoding", "gzip");
            SseClient c = res.sse();
            seen.set(res.isCommitted() + " " + ((DefaultResponse) res).state());
            c.send("connected", "ready");
            c.close();
        });
        ClientResponse r = t.send("GET", "/events");
        assertEquals("true " + ResponseState.STREAMING, seen.get());
        assertEquals(200, r.status());
        assertEquals("text/event-stream; charset=UTF-8", r.header("Content-Type"));
        assertEquals("no-cache, no-transform", r.header("Cache-Control"));
        assertEquals(1, count(r, "Content-Type"));
        assertEquals(1, count(r, "Cache-Control"));
        assertEquals(0, count(r, "Content-Length"));
        assertEquals(0, count(r, "Transfer-Encoding"));
        assertEquals("event: connected\ndata: ready\n\n", text(r));
    }

    // SDS-AC-7; FR-SSE-001 AC-2
    @Test
    public void sdsAc7_FR_SSE_001_AC2_sseOnACommittedResponseAndASecondSseThrowFromTheNewCheck() {
        AtomicReference<String> afterSend = new AtomicReference<>();
        t.app().get("/sent", (req, res) -> {
            res.send("x");
            try {
                res.sse();
                afterSend.set("accepted");
            } catch (IllegalStateException e) {
                afterSend.set(e.getMessage());
            }
        });
        AtomicReference<String> second = new AtomicReference<>();
        t.app().get("/twice", (req, res) -> {
            SseClient c = res.sse();
            try {
                res.sse();
                second.set("accepted");
            } catch (IllegalStateException e) {
                second.set(e.getMessage());
            }
            c.send("one");
            c.close();
        });
        ClientResponse sent = t.send("GET", "/sent");
        assertEquals("response is " + ResponseState.COMPLETED, afterSend.get());
        assertEquals("x", text(sent));
        assertEquals("text/plain; charset=UTF-8", sent.header("Content-Type"));
        ClientResponse twice = t.send("GET", "/twice");
        assertEquals("response is " + ResponseState.STREAMING, second.get());
        assertEquals("data: one\n\n", text(twice));
    }

    // SDS-AC-10; FR-SSE-001 AC-5
    @Test
    public void sdsAc10_FR_SSE_001_AC5_sendOfAnSseEventWritesItsFieldsInOrder() {
        t.app().get("/events", (req, res) -> {
            SseClient c = res.sse();
            c.send(SseEvent.event("progress").retry(3000).id("100").data("{\"progress\":50}"));
            c.close();
        });
        assertEquals("event: progress\nid: 100\nretry: 3000\ndata: {\"progress\":50}\n\n",
                text(t.send("GET", "/events")));
    }

    // SDS-AC-12; FR-SSE-001 AC-7
    @Test
    public void sdsAc12_FR_SSE_001_AC7_dataOnlySendWritesNoEventLineAndParsesAsMessage() {
        t.app().get("/events", (req, res) -> {
            SseClient c = res.sse();
            c.send("only data");
            c.send("m", "a\nb");
            c.close();
        });
        String body = text(t.send("GET", "/events"));
        assertEquals("data: only data\n\nevent: m\ndata: a\ndata: b\n\n", body);
        assertEquals(Arrays.asList("message|only data", "m|a\nb"), parse(body));
    }

    // SDS-AC-13; FR-SSE-001 AC-9
    @Test
    public void sdsAc13_FR_SSE_001_AC9_dataIsWrittenAsUtf8() {
        t.app().get("/events", (req, res) -> {
            SseClient c = res.sse();
            c.send("ko", "한글");
            c.close();
        });
        byte[] body = t.send("GET", "/events").body();
        byte[] prefix = "event: ko\ndata: ".getBytes(StandardCharsets.US_ASCII);
        byte[] hangul = {(byte) 0xED, (byte) 0x95, (byte) 0x9C, (byte) 0xEA, (byte) 0xB8, (byte) 0x80};
        byte[] expected = new byte[prefix.length + hangul.length + 2];
        System.arraycopy(prefix, 0, expected, 0, prefix.length);
        System.arraycopy(hangul, 0, expected, prefix.length, hangul.length);
        expected[expected.length - 2] = '\n';
        expected[expected.length - 1] = '\n';
        assertArrayEquals(expected, body);
    }

    // SDS-AC-13; FR-SSE-001 AC-8
    @Test
    public void sdsAc13_FR_SSE_001_AC8_lastEventIdReachesTheHandlerAndTheReplaySendsOnlyLaterEvents() {
        List<String[]> store = Arrays.asList(new String[] {"41", "a"}, new String[] {"42", "b"},
                new String[] {"43", "c"}, new String[] {"44", "d"});
        List<String> lastIds = new ArrayList<>();
        t.app().get("/events", (req, res) -> {
            String lastId = req.lastEventId();
            synchronized (lastIds) {
                lastIds.add(String.valueOf(lastId));
            }
            SseClient c = res.sse();
            boolean after = lastId == null;
            for (String[] e : store) {
                if (after) {
                    c.send(SseEvent.event("tick").id(e[0]).data(e[1]));
                }
                after |= e[0].equals(lastId);
            }
            c.close();
        });
        String replay = text(t.send("GET", "/events", "Last-Event-ID", "42"));
        assertEquals("event: tick\nid: 43\ndata: c\n\nevent: tick\nid: 44\ndata: d\n\n", replay);
        String fresh = text(t.send("GET", "/events"));
        assertTrue(fresh, fresh.startsWith("event: tick\nid: 41\n"));
        assertEquals(Arrays.asList("42", "null"), lastIds);
    }
}
