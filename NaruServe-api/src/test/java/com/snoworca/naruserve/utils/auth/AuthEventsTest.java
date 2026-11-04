package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.internal.EventCalls;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import org.junit.Test;

/** AuthEvents, AuthEventContext and the internal listener-call helper. */
public class AuthEventsTest {

    private static final AuthUser ALICE = AuthUser.builder().id("1").username("alice").passwordHash("$pbkdf2$x")
            .roles("editor").build();

    private static final AuthEventContext CTX = new AuthEventContext(UserOrigin.APP, null, null, 1000L);

    private static Object[] argsFor(Method m) {
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == AuthUser.class) {
                args[i] = ALICE;
            } else if (t == AuthEventContext.class) {
                args[i] = CTX;
            } else if (t == String.class) {
                args[i] = "1";
            } else if (t == boolean.class) {
                args[i] = Boolean.TRUE;
            } else if (t == java.util.Set.class) {
                args[i] = EnumSet.of(UserChange.USERNAME);
            } else {
                throw new AssertionError("no argument for " + t + " of " + m);
            }
        }
        return args;
    }

    // CON-AUTH-006 AC-3: every AuthEvents method is a default no-op; new AuthEvents() {} compiles and does nothing
    @Test
    public void CON_AUTH_006_AC3_everyAuthEventsMethodIsADefaultNoOp() throws Exception {
        AuthEvents none = new AuthEvents() { };
        Method[] methods = AuthEvents.class.getDeclaredMethods();
        int calls = 0;
        for (Method m : methods) {
            if (m.isSynthetic() || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            assertTrue(m.getName() + " is default", m.isDefault());
            assertNull(m.getName(), m.invoke(none, argsFor(m)));
            calls++;
        }
        assertTrue("the user-change events, login and refresh are declared", calls >= 10);
        for (String name : new String[] {"beforeLogin", "onLogin", "beforeUserCreate", "onUserCreated",
                "beforeUserUpdate", "onUserUpdated", "onPasswordChanged", "beforeUserDelete", "onUserDeleted",
                "onTokenRefreshed"}) {
            boolean found = false;
            for (Method m : methods) {
                found |= m.getName().equals(name);
            }
            assertTrue(name, found);
        }
        // the before/after pair of beforeUserUpdate (FR-AUTH-007 statement)
        Method update = AuthEvents.class.getMethod("beforeUserUpdate", AuthUser.class, AuthUser.class,
                AuthEventContext.class);
        assertEquals(Exception.class, update.getExceptionTypes()[0]);
        // the user AuthUser was not touched by any call
        assertEquals("$pbkdf2$x", ALICE.getPasswordHash());
    }

    // FR-AUTH-007 component: the before helper passes AuthRejection through and wraps anything else as a 503
    @Test
    public void beforeCallsRethrowRejectionsAndWrapOtherExceptions() {
        RecordingLogger log = new RecordingLogger();
        final AuthRejection rejection = new AuthRejection(422, "domain_not_allowed");
        try {
            EventCalls.before(log, "beforeUserCreate", () -> {
                throw rejection;
            });
            fail("not rejected");
        } catch (AuthRejection r) {
            assertSame(rejection, r);
        }
        assertEquals(0, log.lines.size());
        for (final Exception thrown : new Exception[] {new IllegalStateException("boom"),
                new HttpException(403, "nope"), new java.io.IOException("io")}) {
            try {
                EventCalls.before(log, "beforeUserCreate", () -> {
                    throw thrown;
                });
                fail("not wrapped: " + thrown);
            } catch (AuthUnavailableException e) {
                assertSame(thrown, e.getCause());
                assertEquals("SERVICE_UNAVAILABLE", e.getCode());
            }
        }
        assertEquals(3, log.count("ERROR"));
        assertEquals(3, log.errors.size());
    }

    // FR-AUTH-007 component: after-write listeners only log their failures
    @Test
    public void afterCallsOnlyLog() {
        RecordingLogger log = new RecordingLogger();
        EventCalls.after(log, "onUserCreated", () -> {
            throw new IllegalStateException("listener down");
        });
        EventCalls.after(log, "onUserCreated", () -> {
            throw new AuthRejection(422, "too_late");
        });
        assertEquals(2, log.count("ERROR"));
        EventCalls.after(log, "onLogin", () -> { });
        assertEquals(2, log.lines.size());
    }

    // FR-AUTH-007 component: listeners get a copy without the password hash
    @Test
    public void listenerCopiesCarryNoPasswordHash() {
        AuthUser copy = EventCalls.copy(ALICE);
        assertNull(copy.getPasswordHash());
        assertEquals(ALICE.getId(), copy.getId());
        assertEquals(ALICE.getRoles(), copy.getRoles());
        assertNotNull(ALICE.getPasswordHash());
    }

    // FR-AUTH-007 component: the event context and its nullable origin
    @Test
    public void eventContextExposesItsFields() {
        AuthEventContext ctx = new AuthEventContext(null, null, "admin-7", 42L);
        assertNull(ctx.getOrigin());
        assertNull(ctx.getRequest());
        assertEquals("admin-7", ctx.getActorId());
        assertEquals(42L, ctx.getTimestamp());
        assertEquals(UserOrigin.APP, CTX.getOrigin());
        assertEquals(EnumSet.of(UserOrigin.REGISTER, UserOrigin.ADMIN, UserOrigin.APP, UserOrigin.SELF,
                UserOrigin.SETUP, UserOrigin.RECOVERY, UserOrigin.BOOTSTRAP), EnumSet.allOf(UserOrigin.class));
    }
}
