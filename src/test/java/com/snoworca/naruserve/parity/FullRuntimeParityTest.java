package com.snoworca.naruserve.parity;
import com.google.gson.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;
/** All committed reference scenarios execute against NaruServe, including pending features (real RED). */
@RunWith(Parameterized.class)
public class FullRuntimeParityTest {
    @Parameterized.Parameters(name="{0}") public static Collection<Object[]> cases() throws Exception {
        JsonObject corpus=ParityCorpusTest.resource("express5.json");
        ParityCorpus.validate(corpus,ParityCorpusTest.resource("reference-lock.json"),ParityCorpusTest.generatorHash());
        List<Object[]> cases=new ArrayList<>();
        for(JsonElement item:corpus.getAsJsonArray("cases")) {
            JsonObject c=item.getAsJsonObject();cases.add(new Object[]{c.get("id").getAsString(),c});
        }
        assertEquals("All actual pinned reference cases are replayed",30,cases.size());return cases;
    }
    private final String id;private final JsonObject reference;
    public FullRuntimeParityTest(String id,JsonObject reference){this.id=id;this.reference=reference;}
    // OPS-ROUTE-004 AC-2 AC-3 AC-6 AC-7: status, bytes, headers, frames, captures and errors are actual observations.
    @Test public void actualJavaRuntimeMatchesPinnedExpressScenario() throws Exception {
        LanguageErrorCounterparts.compare(id,reference,RuntimeReplay.run(reference));
    }
}
