package com.snoworca.naruserve;

import static com.snoworca.naruserve.WebSocketDispatchTest.accepted;
import static com.snoworca.naruserve.WebSocketDispatchTest.upgradeRequest;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.InMemoryWebSocketChannel;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** WebSocketSession semantics over the in-memory adapter. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-5.sds.md. */
public class WebSocketSessionTest {

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final RecordingLogger logger = new RecordingLogger();
    private final RecordingCodec codec = new RecordingCodec();
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();

    public WebSocketSessionTest() {
        t.app().logger(logger);
        t.app().jsonCodec(codec);
    }

    static final class Msg {
        final String text;

        Msg(String text) {
            this.text = text;
        }
    }

    /**
     * Encodes as "J" + value and decodes any text to a Msg; "{bad" fails with IOException, "{boom" with a
     * RuntimeException. Records each call, and the thread of each encode.
     */
    static final class RecordingCodec implements JsonCodec {
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        volatile boolean failEncode;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(Reader reader, Type type) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (int c = reader.read(); c >= 0; c = reader.read()) {
                sb.append((char) c);
            }
            calls.add("decode " + ((Class<?>) type).getSimpleName() + " " + sb);
            if (sb.toString().startsWith("{bad")) {
                throw new IOException("bad json");
            }
            if (sb.toString().startsWith("{boom")) {
                throw new IllegalArgumentException("codec bug");
            }
            return (T) new Msg(sb.toString());
        }

        @Override
        public void encode(Object value, Writer writer) throws IOException {
            calls.add("encode " + value + " on " + Thread.currentThread().getName());
            if (failEncode) {
                throw new IOException("encode broke");
            }
            writer.write("J" + value);
        }
    }

    private String event() throws InterruptedException {
        return events.poll(5, TimeUnit.SECONDS);
    }

    /** Waits until the channel recorded {@code n} sent messages and returns them. */
    private static List<Object> sent(InMemoryWebSocketChannel ch, int n) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Object> s = ch.sent();
        while (s.size() < n && System.nanoTime() < end) {
            Thread.sleep(10);
            s = ch.sent();
        }
        return s;
    }

    private boolean logged(String regex) {
        for (String line : new ArrayList<>(logger.lines)) {
            if (line.matches(regex)) {
                return true;
            }
        }
        return false;
    }

    // SDS-AC-1; FR-WS-006 AC-1: ids count up from 1 per app, are distinct under concurrency and restart in a new app
    @Test(timeout = 60000)
    public void sdsAc1_FR_WS_006_AC1_idsAreSixteenHexDigitsOfAnAppWideCounter() throws Exception {
        Set<String> ids = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        List<String> first = Collections.synchronizedList(new ArrayList<String>());
        t.app().ws("/ws", (req, ws) -> {
            first.add(ws.id());
            ids.add(ws.id());
            ws.close();
        });
        t.start();
        for (int i = 0; i < 3; i++) {
            assertEquals(101, adapter.send(upgradeRequest("/ws")).status());
        }
        assertEquals(Arrays.asList("0000000000000001", "0000000000000002", "0000000000000003"), first);

        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<Throwable>());
        for (int i = 0; i < 8; i++) {
            Thread th = new Thread(() -> {
                try {
                    for (int j = 0; j < 125; j++) {
                        assertEquals(101, adapter.send(upgradeRequest("/ws")).status());
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) {
            th.join();
        }
        assertEquals(failures.toString(), 0, failures.size());
        assertEquals(1003, ids.size());
        for (String id : ids) {
            assertTrue(id, id.matches("[0-9a-f]{16}"));
        }

        InMemoryHttpAdapter other = new InMemoryHttpAdapter();
        TestApp second = TestApp.create(other);
        BlockingQueue<String> secondIds = new LinkedBlockingQueue<>();
        second.app().ws("/ws", (req, ws) -> {
            secondIds.add(ws.id());
            ws.close();
        });
        second.start();
        assertEquals(101, other.send(upgradeRequest("/ws")).status());
        assertEquals("0000000000000001", secondIds.poll(5, TimeUnit.SECONDS));
    }

    // SDS-AC-2; FR-WS-006 AC-2: session attributes are the session's own; null removes; a primitive class reads boxed
    @Test(timeout = 20000)
    public void sdsAc2_FR_WS_006_AC2_attributesAreSeparateFromTheRequestAndNullRemoves() throws Exception {
        t.app().ws("/ws", (req, ws) -> {
            ws.attribute("user", "kim");
            ws.attribute("n", 5);
            ws.attribute("gone", "x");
            ws.attribute("gone", (Object) null);
            ws.onText(text -> events.add(ws.attribute("user", String.class) + " "
                    + req.attribute("user", String.class) + " " + ws.attribute("gone", String.class) + " "
                    + ws.attribute("n", int.class)));
        });
        t.start();
        accepted(adapter, upgradeRequest("/ws"), ch -> {
            ch.clientText("read");
            assertEquals("kim null null 5", event());
        });
    }

    // SDS-AC-5; FR-WS-006 AC-4: sendJson encodes on the calling thread through the app codec and sends that text;
    // a codec failure throws with nothing queued
    @Test(timeout = 20000)
    public void sdsAc5_FR_WS_006_AC4_sendJsonSendsTheCodecEncodingAndACodecFailureQueuesNothing() throws Exception {
        t.app().ws("/ws", (req, ws) -> ws.onText(text -> {
            if ("send".equals(text)) {
                ws.sendJson(Collections.singletonMap("a", 1));
            } else if ("fail".equals(text)) {
                codec.failEncode = true;
                try {
                    ws.sendJson("x");
                    events.add("no throw");
                } catch (HttpException e) {
                    events.add("threw " + e.status() + " " + (e.getCause() instanceof IOException));
                }
                ws.sendText("after");
            }
            events.add("thread " + Thread.currentThread().getName());
        }));
        t.start();
        accepted(adapter, upgradeRequest("/ws"), ch -> {
            ch.clientText("send");
            String thread = event().substring("thread ".length());
            assertEquals(Collections.<Object>singletonList("J{a=1}"), sent(ch, 1));
            assertEquals(Collections.singletonList("encode {a=1} on " + thread), codec.calls);
            assertFalse(thread.contains("-ws-sender-"));
            ch.clientText("fail");
            assertEquals("threw 500 true", event());
            assertEquals(Arrays.<Object>asList("J{a=1}", "after"), sent(ch, 2));
        });
    }

    // SDS-AC-6; FR-WS-006 AC-4: onText and onJson listeners run in one registration-ordered list, each onJson
    // decoding through the app codec with its own type
    @Test(timeout = 20000)
    public void sdsAc6_FR_WS_006_AC4_onJsonDecodesThroughTheCodecInRegistrationOrder() throws Exception {
        t.app().ws("/ws", (req, ws) -> {
            ws.onText(text -> events.add("text1 " + text));
            ws.onJson(Msg.class, m -> events.add("json " + m.text));
            ws.onText(text -> events.add("text2 " + text));
        });
        t.start();
        accepted(adapter, upgradeRequest("/ws"), ch -> {
            ch.clientText("{\"m\":1}");
            assertEquals("text1 {\"m\":1}", event());
            assertEquals("json {\"m\":1}", event());
            assertEquals("text2 {\"m\":1}", event());
            assertEquals(Collections.singletonList("decode Msg {\"m\":1}"), codec.calls);
        });
    }

    // SDS-AC-7; FR-WS-006 AC-5: a decode failure skips that listener, goes to onError, runs later listeners and
    // keeps the session open; with no onError listener it is a WARN line with the request id
    @Test(timeout = 20000)
    public void sdsAc7_FR_WS_006_AC5_decodeFailureGoesToOnErrorAndTheSessionStaysOpen() throws Exception {
        AtomicReference<WebSocketSession> session = new AtomicReference<>();
        t.app().ws("/ws", (req, ws) -> {
            session.set(ws);
            ws.onJson(Msg.class, m -> events.add("json called"));
            ws.onText(text -> events.add("later " + text + " " + ws.isOpen()));
            ws.onError(e -> events.add("error " + e.getMessage()));
            ws.onError(e -> events.add("error2 " + e.getMessage()));
        });
        t.app().ws("/quiet", (req, ws) -> {
            ws.onJson(Msg.class, m -> events.add("json called"));
            ws.onText(text -> events.add("quiet later"));
        });
        t.start();
        accepted(adapter, upgradeRequest("/ws"), ch -> {
            ch.clientText("{bad");
            assertEquals("error bad json", event());
            assertEquals("error2 bad json", event());
            assertEquals("later {bad true", event());
            ch.clientText("{boom");
            assertEquals("error codec bug", event());
            assertEquals("error2 codec bug", event());
            assertEquals("later {boom true", event());
            assertTrue(session.get().isOpen());
            assertEquals(-1, ch.closeCode());
        });
        accepted(adapter, upgradeRequest("/quiet"), ch -> {
            ch.clientText("{bad");
            assertEquals("quiet later", event());
        });
        assertTrue(logger.lines.toString(), logged("WARN websocket [0-9a-f]{16} json decode failed.*"));
        assertEquals("only the session without onError logs WARN", 1, logger.count("WARN"));
        assertEquals(0, logger.count("ERROR"));
        assertNull(events.poll());
    }

    // SDS-AC-8; FR-WS-006 AC-6: an onText exception goes to onError, is logged at ERROR with the request id, skips
    // the later listeners and closes 1011, and the adapter's onText call returns normally
    @Test(timeout = 20000)
    public void sdsAc8_FR_WS_006_AC6_listenerExceptionClosesWith1011() throws Exception {
        t.app().ws("/ws", (req, ws) -> {
            ws.onError(e -> events.add("error " + e.getMessage()));
            ws.onText(text -> {
                throw new IllegalStateException("boom");
            });
            ws.onText(text -> events.add("second ran"));
            ws.onClose((code, reason) -> events.add("close " + code + " " + reason));
        });
        t.start();
        AtomicReference<InMemoryWebSocketChannel> channel = new AtomicReference<>();
        accepted(adapter, upgradeRequest("/ws"), ch -> {
            channel.set(ch);
            ch.clientText("x");
            assertEquals("error boom", event());
            assertEquals("close 1011 ", event());
        });
        assertEquals(1011, channel.get().closeCode());
        // The close 1011 event above arrived through the read loop, so onText returned to it normally.
        assertFalse("the in-memory exchange was aborted", channel.get().aborted());
        assertTrue(logger.lines.toString(), logged("ERROR websocket [0-9a-f]{16} listener failed boom"));
        assertNull(events.poll());
    }

    // SDS-AC-8; FR-WS-006 AC-6: a binary listener and a JSON listener that throw after a good decode are handled the
    // same way: onError, ERROR line, close 1011, the adapter's call returns
    @Test(timeout = 20000)
    public void sdsAc8_FR_WS_006_AC6_binaryAndJsonListenerExceptionsCloseWith1011() throws Exception {
        t.app().ws("/bin", (req, ws) -> {
            ws.onError(e -> events.add("error " + e.getMessage()));
            ws.onBinary(data -> {
                throw new IllegalStateException("bin boom");
            });
            ws.onClose((code, reason) -> events.add("close " + code));
        });
        t.app().ws("/json", (req, ws) -> {
            ws.onError(e -> events.add("error " + e.getMessage()));
            ws.onJson(Msg.class, m -> {
                throw new IllegalStateException("json boom " + m.text);
            });
            ws.onClose((code, reason) -> events.add("close " + code));
        });
        t.start();
        List<InMemoryWebSocketChannel> channels = Collections.synchronizedList(new ArrayList<InMemoryWebSocketChannel>());
        accepted(adapter, upgradeRequest("/bin"), ch -> {
            channels.add(ch);
            ch.clientBinary(new byte[] {1});
            assertEquals("error bin boom", event());
            assertEquals("close 1011", event());
        });
        accepted(adapter, upgradeRequest("/json"), ch -> {
            channels.add(ch);
            ch.clientText("ok");
            assertEquals("error json boom ok", event());
            assertEquals("close 1011", event());
        });
        for (InMemoryWebSocketChannel ch : channels) {
            assertEquals(1011, ch.closeCode());
            assertFalse(ch.aborted());
        }
        assertTrue(logger.lines.toString(), logged("ERROR websocket [0-9a-f]{16} listener failed bin boom"));
        assertTrue(logger.lines.toString(), logged("ERROR websocket [0-9a-f]{16} listener failed json boom ok"));
    }

    // SDS-AC-10; FR-WS-006 AC-7: sends and ping on a closed session return quietly, send nothing, encode nothing
    @Test(timeout = 20000)
    public void sdsAc10_FR_WS_006_AC7_sendsOnAClosedSessionAreDropped() throws Exception {
        BlockingQueue<WebSocketSession> sessions = new LinkedBlockingQueue<>();
        t.app().ws("/ws", (req, ws) -> sessions.add(ws));
        t.start();
        AtomicReference<InMemoryWebSocketChannel> channel = new AtomicReference<>();
        accepted(adapter, upgradeRequest("/ws"), ch -> {
            channel.set(ch);
            WebSocketSession ws = sessions.poll(5, TimeUnit.SECONDS);
            ws.close();
            assertFalse(ws.isOpen());
            ws.sendText("a");
            ws.sendBinary(new byte[] {1});
            ws.sendJson(1);
            ws.ping();
            assertFalse(ws.isOpen());
        });
        assertEquals(Collections.emptyList(), channel.get().sent());
        assertEquals(0, channel.get().pings());
        assertEquals(Collections.emptyList(), codec.calls);
        assertEquals(1000, channel.get().closeCode());
    }

    // SDS-AC-10; FR-WS-006 AC-7: on an open session text and binary go out in order, the binary copied at send time
    @Test(timeout = 20000)
    public void sdsAc10_FR_WS_006_AC7_sendsOnAnOpenSessionGoOutInOrder() throws Exception {
        t.app().ws("/ws", (req, ws) -> {
            byte[] data = {1, 2};
            ws.sendText("hi");
            ws.sendBinary(data);
            data[0] = 9;
        });
        t.start();
        accepted(adapter, upgradeRequest("/ws"), ch -> {
            List<Object> s = sent(ch, 2);
            assertEquals("hi", s.get(0));
            assertArrayEquals(new byte[] {1, 2}, (byte[]) s.get(1));
        });
    }

    // SDS-AC-13; FR-WS-006 AC-10: a handler exception after 101 goes to the onError listener registered before it,
    // is logged at ERROR with the request id and closes 1011; the answer stays 101
    @Test(timeout = 20000)
    public void sdsAc13_FR_WS_006_AC10_handlerExceptionClosesWith1011After101() throws Exception {
        t.app().ws("/ws", (req, ws) -> {
            ws.onError(e -> events.add("error " + e.getClass().getSimpleName() + " " + e.getMessage()));
            ws.onClose((code, reason) -> events.add("close " + code + " " + reason));
            throw new IOException("no db");
        });
        t.start();
        AtomicReference<InMemoryWebSocketChannel> channel = new AtomicReference<>();
        ClientResponse r = accepted(adapter, upgradeRequest("/ws"), ch -> {
            channel.set(ch);
            assertEquals("error IOException no db", event());
            assertEquals("close 1011 ", event());
        });
        assertEquals(101, r.status());
        assertEquals(1011, channel.get().closeCode());
        assertFalse(channel.get().aborted());
        assertTrue(logger.lines.toString(), logged("ERROR websocket [0-9a-f]{16} handler failed no db"));
    }
}
