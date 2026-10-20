package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.AppStartException;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.LiveThreads;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletException;
import org.junit.Rule;
import org.junit.Test;

/** A failing onStart fails context startup. */
public class ServletStartFailureTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    static final Exception FAILURE = new Exception("onStart failed on purpose");

    /** An app whose only onStart callback throws a checked exception, which listen wraps in AppStartException. */
    public static final class FailingStart implements ServletConfigurer {
        @Override
        public RestApp configure(RestApp.Builder builder) {
            RestApp app = builder.build();
            app.onStart(ctx -> {
                throw FAILURE;
            });
            return app;
        }
    }

    // SDS-AC-7; FR-SVL-001 AC-7
    @Test
    public void sdsAc7_FR_SVL_001_AC7_failingOnStartFailsContextStartup() {
        List<Thread> before = LiveThreads.withPrefix("naruserve-");
        ServletException e = ServletConfigurerTest.startupFailure(container,
                ServletConfigurerTest.configurer(FailingStart.class));
        Throwable cause = e.getCause();
        assertTrue("cause " + cause, cause instanceof AppStartException);
        assertSame(FAILURE, cause.getCause());
        List<Thread> left = new ArrayList<>(LiveThreads.withPrefix("naruserve-"));
        left.removeAll(before);
        assertEquals("no app thread remains: " + left, 0, left.size());
    }
}
