package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class AccessLogOptionsTest {

    private static final String DEFAULTS = "AccessLogOptions{enabled=true, level=INFO, includeRequestId=true, "
            + "includeDuration=true, includeBytes=true, exclude=[]}";

    private static RestApp newApp() {
        return RestApp.builder().httpAdapter(new InMemoryHttpAdapter())
                .jsonCodec(new RestAppBuilderTest.NoopCodec()).build();
    }

    private static AccessLogOptions capture(RestApp app) {
        List<AccessLogOptions> seen = new ArrayList<>();
        app.accessLog(seen::add);
        assertEquals(1, seen.size());
        return seen.get(0);
    }

    private static void expectNpe(Runnable r) {
        try {
            r.run();
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // expected
        }
    }

    // SDS-AC-12; IR-APP-007 AC-1
    @Test
    public void sdsAc12_IR_APP_007_AC1_optionChainReturnsTheSameOptionsAndAccessLogReturnsTheApp() {
        RestApp app = newApp();
        List<AccessLogOptions> results = new ArrayList<>();
        RestApp returned = app.accessLog(options -> {
            results.add(options);
            results.add(options.enabled(true));
            results.add(options.level(LogLevel.DEBUG));
            results.add(options.includeRequestId(false));
            results.add(options.includeDuration(false));
            results.add(options.includeBytes(false));
            results.add(options.exclude("/health"));
        });
        assertSame(app, returned);
        for (AccessLogOptions o : results) {
            assertSame(results.get(0), o);
        }
        assertSame(app, app.accessLog(options -> options.enabled(true).level(LogLevel.DEBUG)
                .includeRequestId(false).includeDuration(false).includeBytes(false).exclude("/health")));
    }

    // SDS-AC-12; IR-APP-007 AC-2
    @Test
    public void sdsAc12_IR_APP_007_AC2_defaultsAndTwoCallsAccumulateOnTheOneOptionsInOrder() {
        RestApp app = newApp();
        AccessLogOptions first = capture(app);
        assertEquals(DEFAULTS, first.toString());

        app.accessLog(o -> o.level(LogLevel.DEBUG).exclude("/a"));
        app.accessLog(o -> o.level(LogLevel.WARN).exclude("/b").includeBytes(false));
        AccessLogOptions again = capture(app);
        assertSame(first, again);
        assertEquals("AccessLogOptions{enabled=true, level=WARN, includeRequestId=true, includeDuration=true, "
                + "includeBytes=false, exclude=[/a, /b]}", again.toString());
        assertEquals(DEFAULTS, capture(newApp()).toString());
    }

    // SDS-AC-13; IR-APP-007 AC-3
    @Test
    public void sdsAc13_IR_APP_007_AC3_nullConfigurerLevelAndExcludeThrowNullPointerException() {
        RestApp app = newApp();
        expectNpe(() -> app.accessLog(null));
        AccessLogOptions o = capture(app);
        expectNpe(() -> o.level(null));
        expectNpe(() -> o.exclude(null));
        assertEquals(DEFAULTS, o.toString());
    }

    // SDS-AC-14; IR-APP-007 AC-4
    @Test
    public void sdsAc14_IR_APP_007_AC4_configurerIsFunctionalWithOneConfigureMethod() throws Exception {
        assertNotNull(Configurer.class.getAnnotation(FunctionalInterface.class));
        int abstractCount = 0;
        for (java.lang.reflect.Method m : Configurer.class.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) {
                abstractCount++;
            }
        }
        assertEquals(1, abstractCount);
        assertEquals(void.class, Configurer.class.getMethod("configure", Object.class).getReturnType());
    }
}
