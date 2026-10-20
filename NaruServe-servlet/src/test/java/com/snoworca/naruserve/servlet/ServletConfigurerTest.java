package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import javax.servlet.ServletException;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.testing.StubJsonCodec;
import org.eclipse.jetty.util.MultiException;
import org.jspecify.annotations.Nullable;
import org.junit.Rule;
import org.junit.Test;

/** The class named by naruserve.configurer, started through a real web application. */
public class ServletConfigurerTest {

    static final String CONFIGURER = "naruserve.configurer";

    @Rule
    public final ContainerRule container = new ContainerRule();

    /** Builds an app over the Builder it is handed and records the adapter that Builder already held. */
    public static final class Good implements ServletConfigurer {
        static volatile @Nullable Object seenAdapter;

        @Override
        public RestApp configure(RestApp.Builder builder) throws Exception {
            Field adapter = RestApp.Builder.class.getDeclaredField("adapter");
            adapter.setAccessible(true);
            seenAdapter = adapter.get(builder);
            return builder.jsonCodec(new StubJsonCodec()).build();
        }
    }

    /** Not a ServletConfigurer. */
    public static final class NotAConfigurer {
    }

    /** No public no-argument constructor. */
    public static final class NoDefaultConstructor implements ServletConfigurer {
        public NoDefaultConstructor(String unused) {
        }

        @Override
        public RestApp configure(RestApp.Builder builder) {
            throw new AssertionError("never instantiated");
        }
    }

    public static final class Throwing implements ServletConfigurer {
        @Override
        public RestApp configure(RestApp.Builder builder) throws Exception {
            throw new IllegalStateException("configure failed on purpose");
        }
    }

    public static final class ReturnsNull implements ServletConfigurer {
        @Override
        public RestApp configure(RestApp.Builder builder) {
            return null;
        }
    }

    static Map<String, String> configurer(Class<?> c) {
        return Collections.singletonMap(CONFIGURER, c.getName());
    }

    /** Starts a web application that must fail and returns the ServletException that failed it. */
    static ServletException startupFailure(ContainerRule container, Map<String, String> params) {
        try {
            container.webApp(params);
        } catch (Exception e) {
            ServletException found = find(e);
            assertNotNull("no ServletException in " + e, found);
            return found;
        }
        fail("startup did not fail for " + params);
        throw new AssertionError();
    }

    private static @Nullable ServletException find(@Nullable Throwable t) {
        for (; t != null; t = t.getCause()) {
            if (t instanceof ServletException) {
                return (ServletException) t;
            }
            if (t instanceof MultiException) {
                for (Throwable inner : ((MultiException) t).getThrowables()) {
                    ServletException found = find(inner);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    private void assertFailsNaming(Class<?> c) {
        String message = String.valueOf(startupFailure(container, configurer(c)).getMessage());
        assertTrue(message, message.contains(c.getName()));
    }

    private void assertFailsNaming(String className) {
        String message = String.valueOf(startupFailure(container, Collections.singletonMap(CONFIGURER, className))
                .getMessage());
        assertTrue(message, message.contains(className));
    }

    // SDS-AC-6; IR-SVL-001 AC-6
    @Test
    public void sdsAc6_IR_SVL_001_AC6_missingClassFailsStartupNamingIt() {
        assertFailsNaming("com.snoworca.naruserve.servlet.NoSuchConfigurer");
    }

    // SDS-AC-6; IR-SVL-001 AC-6
    @Test
    public void sdsAc6_IR_SVL_001_AC6_classNotImplementingServletConfigurerFails() {
        assertFailsNaming(NotAConfigurer.class);
    }

    // SDS-AC-6; IR-SVL-001 AC-6
    @Test
    public void sdsAc6_IR_SVL_001_AC6_classWithoutPublicNoArgConstructorFails() {
        assertFailsNaming(NoDefaultConstructor.class);
    }

    // SDS-AC-6; IR-SVL-001 AC-6
    @Test
    public void sdsAc6_IR_SVL_001_AC6_throwingConfigureFails() {
        assertFailsNaming(Throwing.class);
    }

    // SDS-AC-6; IR-SVL-001 AC-6
    @Test
    public void sdsAc6_IR_SVL_001_AC6_nullReturningConfigureFails() {
        assertFailsNaming(ReturnsNull.class);
    }

    // SDS-AC-6; IR-SVL-001 AC-6
    @Test
    public void sdsAc6_IR_SVL_001_AC6_goodConfigurerGetsABuilderHoldingAServletAdapter() throws Exception {
        Good.seenAdapter = null;
        container.webApp(configurer(Good.class));
        Object adapter = Good.seenAdapter;
        assertNotNull("configure ran", adapter);
        assertSame(ServletAdapter.class, adapter.getClass());
        assertTrue(adapter instanceof HttpAdapter);
    }
}
