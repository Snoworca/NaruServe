package com.snoworca.naruserve;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.TestApp;
import org.junit.Test;
import static org.junit.Assert.*;

/** FR-DX-001 AC-1..3: ordinary null writes must delete across all attribute stores. */
public class AttributeConvenienceTest {
    @Test public void bareNullRemovesRequestAndApplicationAttributes() throws Exception {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        t.app().get("/attributes", (req, res) -> {
            req.attribute("user", "kim");
            req.attribute("user", null);
            assertNull(req.getClass().getMethod("attributeAs", String.class, Class.class)
                    .invoke(req, "user", String.class));
            ApplicationContext ctx = req.application();
            ctx.attribute("user", "lee");
            ctx.attribute("user", null);
            assertNull(ctx.getClass().getMethod("attributeAs", String.class, Class.class)
                    .invoke(ctx, "user", String.class));
            req.attribute("n", 7);
            assertEquals(Integer.valueOf(7), req.attributeAs("n", int.class));
            req.removeAttribute("n");
            req.removeAttribute("missing");
            assertNull(req.attributeAs("n", Integer.class));
            ctx.attribute("n", 8);
            ctx.removeAttribute("n");
            assertNull(ctx.attributeAs("n", int.class));
            res.send("removed");
        });
        t.start();
        assertEquals("removed", adapter.send(ClientRequest.of("GET", "/attributes")).text());
    }

    @Test public void publicStoresExposeUnambiguousTypedReadsAndExplicitRemoval() throws Exception {
        for (Class<?> type : new Class<?>[]{Request.class, ApplicationContext.class, WebSocketSession.class}) {
            assertNotNull(type.getMethod("attributeAs", String.class, Class.class));
            assertNotNull(type.getMethod("removeAttribute", String.class));
            for (java.lang.reflect.Method method : type.getMethods()) {
                assertFalse("FR-DX-001 old null-trapping overload on " + type,
                        method.getName().equals("attribute") && method.getParameterCount() == 2
                        && method.getParameterTypes()[1] == Class.class);
            }
        }
    }
}
