package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.internal.ShutdownSequenceTest.RecordingLogger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** The internal shutdown participant registry. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-7.sds.md. */
public class ShutdownParticipantsTest {

    // SDS-AC-9; FR-APP-011 AC-3
    @Test
    public void sdsAc9_FR_APP_011_AC3_participantsRunInAddOrderWithinTheirPhaseOnly() {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        ShutdownParticipants p = new ShutdownParticipants();
        p.add(ShutdownParticipants.Phase.FORCE_CLOSE, () -> ran.add("force"));
        p.add(ShutdownParticipants.Phase.BEFORE_WAIT, () -> ran.add("b1"));
        p.add(ShutdownParticipants.Phase.AFTER_ON_STOP, () -> ran.add("after"));
        p.add(ShutdownParticipants.Phase.BEFORE_WAIT, () -> ran.add("b2"));
        RecordingLogger log = new RecordingLogger(ran);
        p.run(ShutdownParticipants.Phase.BEFORE_WAIT, log);
        assertEquals(Arrays.asList("b1", "b2"), ran);
        p.run(ShutdownParticipants.Phase.FORCE_CLOSE, log);
        p.run(ShutdownParticipants.Phase.AFTER_ON_STOP, log);
        assertEquals(Arrays.asList("b1", "b2", "force", "after"), ran);
    }

    // SDS-AC-9; FR-APP-011 AC-3
    @Test
    public void sdsAc9_FR_APP_011_AC3_throwingParticipantIsLoggedAtErrorAndTheNextRuns() {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        ShutdownParticipants p = new ShutdownParticipants();
        p.add(ShutdownParticipants.Phase.AFTER_ON_STOP, () -> {
            throw new IllegalStateException("boom");
        });
        p.add(ShutdownParticipants.Phase.AFTER_ON_STOP, () -> ran.add("next"));
        RecordingLogger log = new RecordingLogger(new ArrayList<>());
        p.run(ShutdownParticipants.Phase.AFTER_ON_STOP, log);
        assertEquals(Arrays.asList("next"), ran);
        assertEquals(1, log.errors.size());
        assertTrue(log.errors.toString(), log.errors.get(0).contains("boom"));
    }
}
