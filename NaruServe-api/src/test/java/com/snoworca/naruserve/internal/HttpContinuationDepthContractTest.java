package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.Middleware;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;
import static com.snoworca.naruserve.internal.HttpExecutionContractTest.text;

/** Exact actual router2.2.0 traces: separate Router/Route bounds, never a single global stack threshold. */
@RunWith(Parameterized.class)
public class HttpContinuationDepthContractTest {
    @Parameterized.Parameters(name="{0} layers={1} callbacks={2}") public static Collection<Object[]> scenarios() throws IOException {
        InputStream resource=HttpContinuationDepthContractTest.class.getResourceAsStream("http-depth-reference.tsv");
        assertNotNull("pinned actual reference traces",resource);List<Object[]> result=new ArrayList<>();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(resource,StandardCharsets.UTF_8))) {
            String line;while((line=reader.readLine())!=null) {
                if(line.startsWith("#") || line.isEmpty())continue;
                String[] values=line.split("\t",4);assertEquals(4,values.length);
                result.add(new Object[]{values[0],Integer.parseInt(values[1]),Integer.parseInt(values[2]),Arrays.asList(values[3].split(","))});
            }
        }
        assertEquals("all11 captured reference scenarios",11,result.size());return result;
    }
    private final int layers,callbacks;private final List<String> expected;
    public HttpContinuationDepthContractTest(String kind,int layers,int callbacks,List<String> expected) {
        this.layers=layers;this.callbacks=callbacks;this.expected=expected;
    }
    @Test(timeout=5000) public void exactPinnedBeforeAndAfterNextTraceUsesIndependentCursorBoundaries() { // FR-APP-022 AC-1 AC-5; FR-APP-023 AC-5
        DefaultRestApp app=new DefaultRestApp();List<String> trace=new ArrayList<>();
        for(int i=0;i<layers;i++) { final int index=i;app.use((request,response,next)->{trace.add("before"+index);next.run();trace.add("after"+index);}); }
        List<Middleware> route=new ArrayList<>();
        for(int i=0;i<callbacks;i++) { final int index=i;route.add((request,response,next)->{trace.add("route-before"+index);next.run();trace.add("route-after"+index);}); }
        route.add((request,response,next)->{trace.add("terminal");response.send("ok");});
        app.method("GET","/x",route.toArray(new Middleware[0]));app.freeze();
        LifecycleExchange exchange=new LifecycleExchange("GET","/x");app.dispatcher().handle(exchange);
        assertEquals("full pinned trace, including every after-next event",expected,trace);
        assertEquals("ok",text(exchange.recording));assertEquals(1,exchange.recording.commits.get());assertEquals(1,exchange.recording.finishes.get());assertEquals(1,exchange.input.closes.get());
    }
}
