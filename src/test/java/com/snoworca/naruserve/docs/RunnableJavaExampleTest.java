package com.snoworca.naruserve.docs;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.common.RunnableExampleContract;
import org.junit.Test;

public class RunnableJavaExampleTest {
    @Test
    public void OPS_DX_001_AC1_AC2_AC3_AC4_javaSourceRunsOverRealHttpFromCleanDirectory() throws Exception {
        Class<?> example = Class.forName("example.JavaExample");
        RunnableExampleContract.verify(uploads -> (RestApp) example.getMethod("create", java.io.File.class).invoke(null, uploads));
    }
}
