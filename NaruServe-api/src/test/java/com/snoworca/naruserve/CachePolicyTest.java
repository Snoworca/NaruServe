package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.SettingsCapturingAdapter;
import com.snoworca.naruserve.testing.StubJsonCodec;
import com.snoworca.naruserve.testing.TestApp;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * CachePolicy, res.cache, app.mimeTypes() and app.defaultCache. SDS-AC-n cites
 * docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-5.sds.md.
 */
public class CachePolicyTest {

    private static DefaultRestApp app() {
        return (DefaultRestApp) RestApp.builder().httpAdapter(new SettingsCapturingAdapter())
                .jsonCodec(new StubJsonCodec()).build();
    }

    // IR-STATIC-002 AC-1
    @Test
    public void IR_STATIC_002_AC1_factoriesRenderTheT32Values() {
        assertEquals("no-store", CachePolicy.noStore().getHeaderValue());
        assertEquals("no-cache", CachePolicy.noCache().getHeaderValue());
        assertEquals("private, max-age=600", CachePolicy.privateFor(10, TimeUnit.MINUTES).getHeaderValue());
        assertEquals("public, max-age=3600", CachePolicy.publicFor(1, TimeUnit.HOURS).getHeaderValue());
        assertEquals("public, max-age=0", CachePolicy.publicFor(0, TimeUnit.SECONDS).getHeaderValue());
    }

    // IR-STATIC-002 AC-1
    @Test
    public void IR_STATIC_002_AC1_immutableReturnsANewPolicyAndKeepsTheReceiver() {
        CachePolicy p = CachePolicy.publicFor(365, TimeUnit.DAYS);
        CachePolicy i = p.immutable();
        assertNotSame(p, i);
        assertEquals("public, max-age=31536000", p.getHeaderValue());
        assertEquals("public, max-age=31536000, immutable", i.getHeaderValue());
        assertEquals("public, max-age=31536000, immutable", i.immutable().getHeaderValue());
    }

    // IR-STATIC-002 AC-1
    @Test
    public void IR_STATIC_002_AC1_finalClassWithoutPublicConstructorOrSetter() {
        assertTrue(Modifier.isFinal(CachePolicy.class.getModifiers()));
        assertEquals(0, CachePolicy.class.getConstructors().length);
        for (java.lang.reflect.Method m : CachePolicy.class.getDeclaredMethods()) {
            assertFalse(m.getName(), m.getName().startsWith("set"));
        }
    }

    // SDS-AC-5; IR-STATIC-002 AC-1
    @Test
    public void sdsAc5_IR_STATIC_002_AC1_negativeAmountRefusedAndEqualityFollowsHeaderValue() {
        try {
            CachePolicy.privateFor(-1, TimeUnit.SECONDS);
            fail("privateFor(-1) accepted");
        } catch (IllegalArgumentException expected) {
            // refused
        }
        try {
            CachePolicy.publicFor(-1, TimeUnit.SECONDS);
            fail("publicFor(-1) accepted");
        } catch (IllegalArgumentException expected) {
            // refused
        }
        CachePolicy a = CachePolicy.publicFor(1, TimeUnit.HOURS);
        CachePolicy b = CachePolicy.publicFor(3600, TimeUnit.SECONDS);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals("public, max-age=3600", a.toString());
        assertNotEquals(a, CachePolicy.privateFor(1, TimeUnit.HOURS));
        assertNotEquals(a, a.immutable());
    }

    // IR-STATIC-002 AC-3
    @Test
    public void IR_STATIC_002_AC3_resCacheSetsCacheControlAndReturnsTheResponse() {
        TestApp t = TestApp.create();
        AtomicReference<Object> returned = new AtomicReference<>();
        t.app().get("/c", (req, res) -> {
            returned.set(res.cache(CachePolicy.noStore()) == res);
            res.send("ok");
        });
        InMemoryHttpAdapter.ClientResponse r = t.send("GET", "/c");
        assertEquals(200, r.status());
        assertEquals("no-store", r.header("Cache-Control"));
        assertEquals(Boolean.TRUE, returned.get());
    }

    // SDS-AC-6; IR-STATIC-002 AC-3
    @Test
    public void sdsAc6_IR_STATIC_002_AC3_cacheAfterSendThrowsIllegalStateException() {
        TestApp t = TestApp.create();
        AtomicReference<Object> outcome = new AtomicReference<>();
        t.app().get("/c", (req, res) -> {
            res.send("ok");
            try {
                res.cache(CachePolicy.noCache());
                outcome.set("accepted");
            } catch (IllegalStateException e) {
                outcome.set(e);
            }
        });
        assertEquals(200, t.send("GET", "/c").status());
        assertTrue(String.valueOf(outcome.get()), outcome.get() instanceof IllegalStateException);
    }

    // IR-STATIC-002 AC-2
    @Test
    public void IR_STATIC_002_AC2_mimeTypesRegisterChainsAndTwoAppsHaveTwoRegistries() {
        DefaultRestApp a = app();
        MimeTypes m = a.mimeTypes();
        assertSame(m, a.mimeTypes());
        assertSame(m, m.register("webp", "image/webp").register(".avif", "image/avif"));
        assertNotSame(m, app().mimeTypes());
    }

    // IR-STATIC-002 AC-4
    @Test
    public void IR_STATIC_002_AC4_defaultCacheReturnsTheAppAndDefaultsToNull() {
        DefaultRestApp app = app();
        assertNull(app.defaultCachePolicy());
        assertSame(app, app.defaultCache(CachePolicy.noCache()));
        assertEquals(CachePolicy.noCache(), app.defaultCachePolicy());
        assertSame(app, app.defaultCache(null));
        assertNull(app.defaultCachePolicy());
    }
}
