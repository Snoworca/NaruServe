package com.snoworca.naruserve.utils.auth.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.testing.RecordingLogger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

/** The audit event stream types (FR-AUTH-009). */
public class AuditEventTest {

    private static AuditEvent sample() {
        return AuditEvent.builder(AuditEvent.SECURITY_WRITE_CONFLICT, "setEnabled").timestampMillis(42L)
                .outcome(AuditEvent.Outcome.FAILURE).actorId("u1").targetId("u2").sourceIp("203.0.113.9")
                .requestId("rid-1").reason("the store did not apply the write").detail("apiKeyId", "k1").build();
    }

    // FR-AUTH-009 AC-1: the fixed getters, with no username, email or password field
    @Test
    public void FR_AUTH_009_AC1_fixedSchemaWithoutPii() throws Exception {
        AuditEvent e = sample();
        assertEquals(42L, e.getTimestampMillis());
        assertEquals("security_write_conflict", e.getType());
        assertEquals(AuditEvent.Outcome.FAILURE, e.getOutcome());
        assertEquals("u1", e.getActorId());
        assertEquals("u2", e.getTargetId());
        assertEquals("setEnabled", e.getAction());
        assertEquals("203.0.113.9", e.getSourceIp());
        assertEquals("rid-1", e.getRequestId());
        assertEquals("the store did not apply the write", e.getReason());
        assertEquals(Collections.singletonMap("apiKeyId", "k1"), e.getDetails());
        assertEquals(long.class, AuditEvent.class.getMethod("getTimestampMillis").getReturnType());
        for (Method m : AuditEvent.class.getMethods()) {
            String n = m.getName().toLowerCase(Locale.ROOT);
            assertFalse(m.toString(), n.contains("username") || n.contains("email") || n.contains("password"));
        }
        for (Field f : AuditEvent.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            assertFalse(f.toString(), n.contains("username") || n.contains("email") || n.contains("password"));
        }
        AuditEvent minimal = AuditEvent.builder(AuditEvent.RECOVERY_USED, "setup").timestampMillis(1L).build();
        assertNull(minimal.getActorId());
        assertNull(minimal.getTargetId());
        assertNull(minimal.getSourceIp());
        assertNull(minimal.getRequestId());
        assertNull(minimal.getReason());
        assertEquals(AuditEvent.Outcome.SUCCESS, minimal.getOutcome());
        assertTrue(minimal.getDetails().isEmpty());
    }

    // FR-AUTH-009 AC-6: AuditEvent, AuditSink and AuditSinks are public in utils.auth.audit; AuditSinks offers public
    // static factories returning an AuditSink
    @Test
    public void FR_AUTH_009_AC6_publicTypesAndFactories() throws Exception {
        for (String n : new String[] {"AuditEvent", "AuditSink", "AuditSinks"}) {
            Class<?> c = Class.forName("com.snoworca.naruserve.utils.auth.audit." + n);
            assertTrue(n, Modifier.isPublic(c.getModifiers()));
        }
        assertTrue(AuditSink.class.isInterface());
        assertTrue(AuditSink.class.isAnnotationPresent(FunctionalInterface.class));
        int factories = 0;
        for (Method m : AuditSinks.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())
                    && m.getReturnType() == AuditSink.class) {
                factories++;
            }
        }
        assertTrue(factories >= 1);
        RecordingLogger log = new RecordingLogger();
        AuditSinks.logger(log).record(sample());
        assertEquals(1, log.lines.size());
        String line = log.lines.get(0);
        assertTrue(line, line.startsWith("INFO audit ") && line.contains("type=security_write_conflict")
                && line.contains("outcome=FAILURE") && line.contains("actor=u1") && line.contains("rid=rid-1"));
        final List<String> seen = new ArrayList<>();
        AuditSink all = AuditSinks.all(e -> seen.add("a:" + e.getType()), e -> seen.add("b:" + e.getType()));
        all.record(sample());
        assertEquals(2, seen.size());
        AuditSinks.none().record(sample());
        RecordingLogger forged = new RecordingLogger();
        AuditSinks.logger(forged).record(AuditEvent.builder(AuditEvent.SECURITY_WRITE_CONFLICT,
                "POST /x\nINFO audit type=fake").build());
        assertFalse(forged.lines.get(0), forged.lines.get(0).contains("\n"));
    }

    // FR-AUTH-009 AC-7: AuditEvent is final, has no public setter and only final instance fields
    @Test
    public void FR_AUTH_009_AC7_eventIsImmutable() {
        assertTrue(Modifier.isFinal(AuditEvent.class.getModifiers()));
        for (Method m : AuditEvent.class.getMethods()) {
            assertFalse(m.toString(), m.getName().startsWith("set"));
        }
        for (Field f : AuditEvent.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                assertTrue(f.toString(), Modifier.isFinal(f.getModifiers()));
            }
        }
        try {
            sample().getDetails().put("x", "y");
            fail("the details map could be changed");
        } catch (UnsupportedOperationException expected) {
            // unmodifiable
        }
    }
}
