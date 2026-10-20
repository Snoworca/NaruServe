package com.snoworca.naruserve;

import static org.junit.Assert.*;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.TestApp;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Exercises real framework stores rather than a replacement Request or WebSocketSession. */
public class AttributeStoreContractTest {
    private interface Store {
        void put(String key, Object value);
        <T> T get(String key, Class<T> type);
        void remove(String key);
    }

    // FR-DX-001 AC-2; FR-DX-001 AC-3: the same typed lookup/removal boundaries apply to every store.
    private static void verify(Store store) {
        store.put("value", "first");
        assertEquals("first", store.get("value", String.class));
        assertThrows(ClassCastException.class, () -> store.get("value", Integer.class));
        store.put("value", null);
        assertNull(store.get("value", String.class));
        store.remove("missing");
        store.put("value", 7);
        assertEquals(Integer.valueOf(7), store.get("value", int.class));
        assertEquals(Integer.valueOf(7), store.get("value", Integer.class));
        store.remove("value");
        assertNull(store.get("value", int.class));
        assertThrows(NullPointerException.class, () -> store.get("value", null));
    }

    // FR-DX-001 AC-1; FR-DX-001 AC-2; FR-DX-001 AC-3
    @Test public void realRequestAndApplicationStoresShareTypedAndRemovalSemantics() {
        TestApp app = TestApp.create();
        app.app().get("/stores", (req, res) -> {
            req.attribute("bare", "value");
            req.attribute("bare", null);
            assertNull(req.attributeAs("bare", String.class));
            verify(new Store() {
                public void put(String k, Object v) { req.attribute(k, v); }
                public <T> T get(String k, Class<T> t) { return req.attributeAs(k, t); }
                public void remove(String k) { req.removeAttribute(k); }
            });
            ApplicationContext context = req.application();
            context.attribute("bare", "value");
            context.attribute("bare", null);
            assertNull(context.attributeAs("bare", String.class));
            verify(new Store() {
                public void put(String k, Object v) { context.attribute(k, v); }
                public <T> T get(String k, Class<T> t) { return context.attributeAs(k, t); }
                public void remove(String k) { context.removeAttribute(k); }
            });
            res.send("verified");
        });
        try { assertEquals("verified", app.send("GET", "/stores").text()); }
        finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }

    // FR-DX-001 AC-1; FR-DX-001 AC-2; FR-DX-001 AC-3
    @Test(timeout = 15000) public void acceptedWebSocketSessionUsesTheSameActualStoreContract() throws Exception {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp app = TestApp.create(adapter);
        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        app.app().ws("/stores", (req, ws) -> {
            try {
                ws.attribute("bare", "value");
                ws.attribute("bare", null);
                assertNull(ws.attributeAs("bare", String.class));
                verify(new Store() {
                    public void put(String k, Object v) { ws.attribute(k, v); }
                    public <T> T get(String k, Class<T> t) { return ws.attributeAs(k, t); }
                    public void remove(String k) { ws.removeAttribute(k); }
                });
            } catch (Throwable error) { failure.set(error); }
            finally { checked.countDown(); }
        });
        app.start();
        try {
            assertEquals(101, WebSocketDispatchTest.accepted(adapter, ClientRequest.upgrade("/stores"), channel -> {
                assertTrue("session handler must execute", checked.await(5, TimeUnit.SECONDS));
                if (failure.get() != null) throw new AssertionError("WebSocket attribute contract", failure.get());
            }).status());
        } finally { app.app().shutdown(5, TimeUnit.SECONDS); }
    }
}
