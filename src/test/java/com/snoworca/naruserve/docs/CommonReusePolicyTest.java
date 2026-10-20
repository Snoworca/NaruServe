package com.snoworca.naruserve.docs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/** CON-DX-001 AC-2: repository contributors must discover the shared-code policy. */
public class CommonReusePolicyTest {
    @Test public void sharedCodePolicyIsPresentInAgentInstructions() throws Exception {
        String policy = new String(Files.readAllBytes(Paths.get("AGENTS.md")), StandardCharsets.UTF_8);
        assertTrue("CON-DX-001 AC-2 duplicate-code rule", policy.contains("Do not duplicate code"));
        assertTrue("CON-DX-001 AC-2 common package", policy.contains("com.snoworca.naruserve.common"));
        assertTrue("CON-DX-001 AC-2 reuse first", policy.contains("Reuse existing utilities"));
    }
}
