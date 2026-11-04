package com.snoworca.naruserve.utils.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import org.junit.Test;

/** The supportsAtomicFieldWrites doctor item (REL-IAM-003). */
public class AtomicFieldWritesDoctorTest {

    // REL-IAM-003 AC-3: under SINGLE_INSTANCE a store returning false from supportsAtomicFieldWrites() passes the item
    @Test
    public void REL_IAM_003_AC3_singleInstanceIgnoresTheDeclaration() {
        CarriedAcceptanceTest.DroppingStore store = new CarriedAcceptanceTest.DroppingStore();
        assertFalse(store.supportsAtomicFieldWrites());
        RecordingLogger log = new RecordingLogger();
        Auth auth = AuthTestSupport.builder(new AuthTestSupport.TestCredentials(), log).users(store).build();
        TestApp t = TestApp.create();
        auth.install(t.app());
        t.start();
        assertEquals(0, RecordingLogger.countMatching(log.lines, ".*supportsAtomicFieldWrites.*"));
        assertEquals(0, log.count("ERROR"));
    }
}
