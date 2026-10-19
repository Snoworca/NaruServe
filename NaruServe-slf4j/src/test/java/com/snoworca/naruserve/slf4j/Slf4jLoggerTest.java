package com.snoworca.naruserve.slf4j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.slf4j.impl.StaticLoggerBinder;

/** SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-9.sds.md. */
public class Slf4jLoggerTest {

    /**
     * Hand-written recording {@code org.slf4j.Logger} (test scope only): every call is recorded as
     * {@code name(arg, ...)}; the boolean level queries answer true.
     */
    private static org.slf4j.Logger recording(List<String> calls) {
        return (org.slf4j.Logger) Proxy.newProxyInstance(Slf4jLoggerTest.class.getClassLoader(),
                new Class<?>[] {org.slf4j.Logger.class}, (proxy, method, args) -> {
                    if (method.getReturnType() == boolean.class) {
                        return true;
                    }
                    if (method.getReturnType() == String.class) {
                        return "recording";
                    }
                    calls.add(method.getName() + (args == null ? "()" : Arrays.toString(args)));
                    return null;
                });
    }

    // SDS-AC-12; FR-APP-018 AC-1, FR-APP-018 AC-4
    @Test
    public void sdsAc12_FR_APP_018_AC1_AC4_sixMethodsForwardOnceWithTheSameMessageAndThrowable() {
        List<String> calls = new ArrayList<>();
        Slf4jLogger logger = new Slf4jLogger(recording(calls));
        IllegalStateException boom = new IllegalStateException("boom");
        logger.trace("t");
        logger.debug("d");
        logger.info("i");
        logger.warn("w");
        logger.error("e");
        logger.error("e2", boom);
        assertEquals(Arrays.asList("trace[t]", "debug[d]", "info[i]", "warn[w]", "error[e]", "error[e2, " + boom + "]"),
                calls);
    }

    // SDS-AC-13; FR-APP-018 AC-2
    @Test
    public void sdsAc13_FR_APP_018_AC2_defaultConstructorRequestsNaruServeAndNullDelegateThrowsNpe() {
        new Slf4jLogger().info("hello");
        assertTrue(StaticLoggerBinder.REQUESTED_NAMES.toString(), StaticLoggerBinder.REQUESTED_NAMES.contains("NaruServe"));
        try {
            new Slf4jLogger(null);
            fail("null delegate accepted");
        } catch (NullPointerException expected) {
            // the delegate is required
        }
    }
}
