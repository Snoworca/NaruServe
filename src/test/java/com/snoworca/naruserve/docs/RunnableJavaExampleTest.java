package com.snoworca.naruserve.docs;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.common.RunnableExampleContract;
import org.junit.Test;

public class RunnableJavaExampleTest {
    // CON-APP-010 AC-4 AC-5; FR-APP-024 AC-3: shared contract checks current HTML failures.
    @Test
    public void OPS_DX_001_AC1_AC2_AC3_AC4_javaSourceRunsOverRealHttpFromCleanDirectory() throws Exception {
        Class<?> example = Class.forName("example.JavaExample");
        RunnableExampleContract.verify(uploads -> (RestApp) example.getMethod("create", java.io.File.class).invoke(null, uploads));
    }
}
