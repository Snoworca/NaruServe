package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.internal.DefaultRestApp;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class RestAppBuilderTest {

    static final class NoopCodec implements JsonCodec {
        @Override
        public <T> T decode(Reader reader, Type type) {
            throw new UnsupportedOperationException("decode");
        }

        @Override
        public void encode(@Nullable Object value, Writer writer) {
            throw new UnsupportedOperationException("encode");
        }
    }

    static final class RecordingLogger implements Logger {
        @Override
        public void trace(String message) {
        }

        @Override
        public void debug(String message) {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Throwable error) {
        }
    }

    // SDS-AC-1; IR-APP-001 AC-1
    @Test
    public void sdsAc1_IR_APP_001_AC1_twoBuildsGiveDistinctNewDefaultRestAppsSharingTheConfiguration() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        JsonCodec codec = new NoopCodec();
        Logger logger = new RecordingLogger();
        RestApp.Builder builder = RestApp.builder().httpAdapter(adapter).jsonCodec(codec).logger(logger);
        RestApp a = builder.build();
        RestApp b = builder.build();

        assertNotSame(a, b);
        for (RestApp app : new RestApp[] {a, b}) {
            assertEquals(DefaultRestApp.class, app.getClass());
            DefaultRestApp d = (DefaultRestApp) app;
            assertSame(adapter, d.httpAdapter());
            assertSame(codec, d.jsonCodec());
            assertSame(logger, d.logger());
            // NEW: still configurable and not frozen.
            assertSame(app, app.get("/x", (req, res) -> res.send("x")));
            try {
                d.dispatcher();
                fail("a freshly built app must not be frozen");
            } catch (IllegalStateException expected) {
            }
        }
    }

    // SDS-AC-1; IR-APP-001 AC-1
    @Test
    public void sdsAc1_IR_APP_001_AC1_builderIsAPublicFinalNestedClassAndEachCallIsNew() {
        int mods = RestApp.Builder.class.getModifiers();
        assertTrue(Modifier.isPublic(mods));
        assertTrue(Modifier.isFinal(mods));
        assertTrue(Modifier.isStatic(mods));
        assertNotSame(RestApp.builder(), RestApp.builder());
        RestApp.Builder b = RestApp.builder();
        assertSame(b, b.httpAdapter(new InMemoryHttpAdapter()));
        assertSame(b, b.jsonCodec(new NoopCodec()));
        assertSame(b, b.logger(new RecordingLogger()));
    }

    // SDS-AC-2; IR-APP-001 AC-2
    @Test
    public void sdsAc2_IR_APP_001_AC2_missingAdapterIsReportedFirstThenMissingCodec() {
        try {
            RestApp.builder().build();
            fail("no adapter, no codec");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("httpAdapter(...)"));
        }
        try {
            RestApp.builder().jsonCodec(new NoopCodec()).build();
            fail("no adapter");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("httpAdapter(...)"));
        }
        try {
            RestApp.builder().httpAdapter(new InMemoryHttpAdapter()).build();
            fail("no codec");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("jsonCodec(...)"));
        }
    }

    // SDS-AC-2; IR-APP-001 AC-2
    @Test
    @SuppressWarnings("ConstantConditions")
    public void sdsAc2_IR_APP_001_AC2_nullSetterArgumentsThrowNullPointerExceptionAtTheSetter() {
        RestApp.Builder b = RestApp.builder();
        try {
            b.httpAdapter(nullOf());
            fail("httpAdapter(null)");
        } catch (NullPointerException e) {
            assertEquals("adapter", e.getMessage());
        }
        try {
            b.jsonCodec(nullOf());
            fail("jsonCodec(null)");
        } catch (NullPointerException e) {
            assertEquals("codec", e.getMessage());
        }
        try {
            b.logger(nullOf());
            fail("logger(null)");
        } catch (NullPointerException e) {
            assertEquals("logger", e.getMessage());
        }
    }

    // SDS-AC-3; IR-APP-001 AC-3
    @Test
    public void sdsAc3_IR_APP_001_AC3_withoutLoggerEachBuildGetsItsOwnNewConsoleLogger() {
        RestApp.Builder b = RestApp.builder().httpAdapter(new InMemoryHttpAdapter()).jsonCodec(new NoopCodec());
        Logger first = ((DefaultRestApp) b.build()).logger();
        Logger second = ((DefaultRestApp) b.build()).logger();
        assertEquals(ConsoleLogger.class, first.getClass());
        assertEquals(ConsoleLogger.class, second.getClass());
        assertNotSame(first, second);
    }

    // SDS-AC-3; IR-APP-001 AC-3
    @Test
    public void sdsAc3_IR_APP_001_AC3_givenLoggerIsTheAppLogger() {
        Logger l = new RecordingLogger();
        RestApp app = RestApp.builder().httpAdapter(new InMemoryHttpAdapter()).jsonCodec(new NoopCodec()).logger(l)
                .build();
        assertSame(l, ((DefaultRestApp) app).logger());
    }

    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
    private static <T> T nullOf() {
        return (T) (Object) null;
    }
}
