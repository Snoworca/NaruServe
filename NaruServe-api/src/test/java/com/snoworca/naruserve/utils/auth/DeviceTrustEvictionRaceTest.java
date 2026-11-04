package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.spi.DeviceToken;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** The in-memory device trust at its cap never loses an invalidation to a concurrent eviction (SEC-FLOW-013). */
public class DeviceTrustEvictionRaceTest {

    // SEC-FLOW-013 / REL-FLOW-001 AC-1: an eviction that chose a token which another failure then invalidates keeps
    // the invalidation and drops the next candidate instead; the failure that reported "invalidated" stays true
    @Test
    public void SEC_FLOW_013_invalidationRacingACapEvictionIsKept() {
        final AtomicLong now = new AtomicLong(1_000_000L);
        RecordingLogger log = new RecordingLogger();
        final InMemoryDeviceTrust trust = new InMemoryDeviceTrust(now::get, 2, log);
        long exp = 10_000_000L;
        final DeviceToken t1 = new DeviceToken("t1", "u", 0, exp);
        DeviceToken t0 = new DeviceToken("t0", "u", 0, exp);
        DeviceToken t2 = new DeviceToken("t2", "u", 0, exp);
        assertFalse(trust.recordFailure(t1, 2, 60_000L)); // the oldest window: the eviction's first choice
        now.addAndGet(10);
        assertFalse(trust.recordFailure(t0, 2, 60_000L));
        now.addAndGet(10);
        final boolean[] reported = new boolean[1];
        trust.beforeDrop = () -> {
            trust.beforeDrop = null;
            // another request's second failure on t1 lands after the victim was chosen and before it is dropped
            reported[0] = trust.recordFailure(t1, 2, 60_000L);
        };
        trust.recordFailure(t2, 2, 60_000L);
        assertTrue("the racing failure invalidated t1", reported[0]);
        assertTrue("the invalidation reported to the caller is kept", trust.isInvalidated(t1));
        assertTrue(trust.snapshot().toString(), trust.snapshot().containsKey("t1"));
        assertFalse("the next candidate not invalidated is dropped instead", trust.snapshot().containsKey("t0"));
        assertEquals(2, trust.snapshot().size());
    }
}
