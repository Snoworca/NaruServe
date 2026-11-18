package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/** The public shape and status mapping of {@link ProxyException} (IR-PROXY-002). */
public class ProxyExceptionTest {

    private final InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
    private final TestApp t = TestApp.create(adapter);
    private final RestApp app = t.app();
    private boolean started;

    @After
    public void tearDown() {
        if (started) {
            app.shutdown(2, TimeUnit.SECONDS);
        }
    }

    // IR-PROXY-002 AC-1: a public final class extending HttpException, in package com.snoworca.naruserve.proxy
    @Test
    public void ir_PROXY_002_AC1_isAPublicFinalSubclassOfHttpException() {
        int mod = ProxyException.class.getModifiers();
        assertEquals("com.snoworca.naruserve.proxy.ProxyException", ProxyException.class.getName());
        assertTrue("public", Modifier.isPublic(mod));
        assertTrue("final", Modifier.isFinal(mod));
        assertSame("direct superclass", HttpException.class, ProxyException.class.getSuperclass());
    }

    // IR-PROXY-002 AC-1: the nested public enum ProxyException.Kind holds exactly the seven kinds, in order
    @Test
    public void ir_PROXY_002_AC1_kindIsANestedPublicEnumOfTheSevenKinds() {
        Class<ProxyException.Kind> kind = ProxyException.Kind.class;
        assertTrue("enum", kind.isEnum());
        assertTrue("public", Modifier.isPublic(kind.getModifiers()));
        assertSame("nested in ProxyException", ProxyException.class, kind.getEnclosingClass());
        assertEquals("com.snoworca.naruserve.proxy.ProxyException$Kind", kind.getName());
        List<String> names = new ArrayList<>();
        for (ProxyException.Kind k : ProxyException.Kind.values()) {
            names.add(k.name());
        }
        assertArrayEquals(new String[] {"CONNECT_FAILED", "CONNECT_TIMEOUT", "WRITE_TIMEOUT", "READ_TIMEOUT",
                "TOTAL_TIMEOUT", "INVALID_RESPONSE", "CONCURRENCY_LIMIT"}, names.toArray(new String[0]));
    }

    // IR-PROXY-002 AC-1: kind() is a public accessor returning the kind given at construction, for every kind
    @Test
    public void ir_PROXY_002_AC1_kindAccessorReturnsTheConstructedKind() throws NoSuchMethodException {
        Method m = ProxyException.class.getMethod("kind");
        assertTrue("public", Modifier.isPublic(m.getModifiers()));
        assertSame(ProxyException.Kind.class, m.getReturnType());
        for (ProxyException.Kind k : ProxyException.Kind.values()) {
            assertSame(k, new ProxyException(k, null).kind());
            assertSame(k, new ProxyException(k, new IOException("x")).kind());
        }
    }

    // IR-PROXY-002 AC-2: status() is 502 for CONNECT_FAILED and INVALID_RESPONSE, 504 for the four timeouts and
    // 503 for CONCURRENCY_LIMIT
    @Test
    public void ir_PROXY_002_AC2_statusDerivesFromTheKind() {
        Map<ProxyException.Kind, Integer> expected = new EnumMap<>(ProxyException.Kind.class);
        expected.put(ProxyException.Kind.CONNECT_FAILED, 502);
        expected.put(ProxyException.Kind.INVALID_RESPONSE, 502);
        expected.put(ProxyException.Kind.CONNECT_TIMEOUT, 504);
        expected.put(ProxyException.Kind.WRITE_TIMEOUT, 504);
        expected.put(ProxyException.Kind.READ_TIMEOUT, 504);
        expected.put(ProxyException.Kind.TOTAL_TIMEOUT, 504);
        expected.put(ProxyException.Kind.CONCURRENCY_LIMIT, 503);
        assertEquals("every kind has an expected status", ProxyException.Kind.values().length, expected.size());
        for (ProxyException.Kind k : ProxyException.Kind.values()) {
            assertEquals(k.name(), (int) expected.get(k), new ProxyException(k, null).status());
            assertEquals(k.name(), (int) expected.get(k), new ProxyException(k, new IOException("x")).status());
        }
    }

    // IR-PROXY-002 AC-3: getMessage() is null for every kind, with or without a cause
    @Test
    public void ir_PROXY_002_AC3_messageIsNullForEveryKind() {
        for (ProxyException.Kind k : ProxyException.Kind.values()) {
            assertNull(k.name(), new ProxyException(k, null).getMessage());
            assertNull(k.name(), new ProxyException(k, new IOException("backend 10.0.0.1:8080")).getMessage());
        }
    }

    // IR-PROXY-002 AC-3: getCause() is the given cause, null when null was given
    @Test
    public void ir_PROXY_002_AC3_causeIsTheGivenCauseOrNull() {
        for (ProxyException.Kind k : ProxyException.Kind.values()) {
            IOException cause = new IOException("x");
            assertSame(k.name(), cause, new ProxyException(k, cause).getCause());
            assertNull(k.name(), new ProxyException(k, null).getCause());
        }
    }

    // IR-PROXY-002 AC-4: exactly one public constructor, ProxyException(ProxyException.Kind, Throwable), pinned by
    // reflection
    @Test
    public void ir_PROXY_002_AC4_exactlyOnePublicConstructorOfKindAndCause() {
        Constructor<?>[] ctors = ProxyException.class.getConstructors();
        assertEquals("public constructors", 1, ctors.length);
        assertArrayEquals(new Class<?>[] {ProxyException.Kind.class, Throwable.class}, ctors[0].getParameterTypes());
        assertNotNull("the cause parameter is @Nullable",
                ctors[0].getAnnotatedParameterTypes()[1].getAnnotation(Nullable.class));
    }

    // IR-PROXY-002 AC-4: an app throws new ProxyException(READ_TIMEOUT, null) through next.error(e) and onError
    // receives it with kind() READ_TIMEOUT
    @Test
    public void ir_PROXY_002_AC4_nextErrorDeliversItToOnErrorWithItsKind() {
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
        final ProxyException thrown = new ProxyException(ProxyException.Kind.READ_TIMEOUT, null);
        app.use((req, res, next) -> next.error(thrown));
        app.onError((error, req, res) -> {
            errors.add(error);
            res.status(((HttpException) error).status()).send("timeout");
        });

        started = true;
        ClientResponse r = t.send("GET", "/x");

        assertEquals("errors that reached onError: " + errors, 1, errors.size());
        assertSame(thrown, errors.get(0));
        assertTrue(errors.get(0) instanceof ProxyException);
        assertSame(ProxyException.Kind.READ_TIMEOUT, ((ProxyException) errors.get(0)).kind());
        assertEquals(504, r.status());
    }
}
