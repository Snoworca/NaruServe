package com.snoworca.naruserve.filelog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** SDS-AC-11 cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-9.sds.md. */
public class FailedListenWriterThreadTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    // SDS-AC-11; FR-APP-010 AC-6, FR-APP-017 AC-7
    @Test
    public void sdsAc11_FR_APP_010_AC6_noWriterThreadRemainsAfterAFailedListen() throws Exception {
        File dir = tmp.newFolder("logs");
        RotatingFileLogger logger = new RotatingFileLogger(RotatingFileLogger.builder().directory(dir),
                (file, append) -> new java.io.FileOutputStream(file, append));
        RestApp app = RestApp.builder().httpAdapter(new InMemoryHttpAdapter()).jsonCodec(new StubJsonCodec())
                .logger(logger).build();
        app.onStart(ctx -> {
            throw new IllegalStateException("start broke");
        });
        assertTrue(logger.writer.isAlive());
        try {
            app.listen(0);
            fail("listen should have failed");
        } catch (IllegalStateException expected) {
            // the onStart cause, rethrown as is
        }
        assertEquals(AppState.TERMINATED, app.state());
        assertFalse("the writer thread is gone", logger.writer.isAlive());
    }
}
