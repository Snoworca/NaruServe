package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.*;
import com.snoworca.naruserve.testing.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static com.snoworca.naruserve.internal.HttpExecutionContractTest.*;

/** Group buffering retains its configuration lifetime, while each builder owns one ordered HTTP Route. */
public class HttpGroupIdentityContractTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void routeCreationPositionPrecedesASeparateRegistrationEvenWhenPopulatedLater() { // FR-APP-021 AC-1 AC-2 AC-3; FR-DX-009 AC-1
        DefaultRestApp app=new DefaultRestApp();app.group("/api",group->{RouteBuilder first=group.route("/x");group.get("/x",(q,s)->s.send("separate"));first.get((q,s)->s.send("first"));});
        assertEquals("first",text(dispatch(app,"GET","/api/x")));
    }
    @Test public void routeSkipExitsEveryRemainingCallbackOfOnlyTheSameGroupBuilder() { // FR-APP-021 AC-2 AC-3; FR-APP-022 AC-2
        DefaultRestApp app=new DefaultRestApp();List<String> trace=new ArrayList<>();app.group("/api",group->{
            RouteBuilder first=group.route("/x");first.get((q,s,n)->{trace.add("skip");n.route();});first.get((q,s)->fail("same group Route resumed after skip"));
            group.get("/x",(q,s)->{trace.add("separate");s.send("ok");});
        });
        assertEquals("ok",text(dispatch(app,"GET","/api/x")));assertEquals(Arrays.asList("skip","separate"),trace);
    }
    @Test public void groupBuilderThreeArgumentCallbacksAndExtendedMethodShareThePublicContract() { // FR-APP-021 AC-3; FR-HTTP-028 AC-1
        DefaultRestApp app=new DefaultRestApp();List<String> trace=new ArrayList<>();app.group("/api",group->{
            group.route("/x").method("COPY",(q,s,n)->{trace.add("first");assertEquals("COPY",q.methodToken());n.run();},(q,s,n)->{trace.add("second");s.send("copied");});
        });
        assertEquals("copied",text(dispatch(app,"COPY","/api/x")));assertEquals(Arrays.asList("first","second"),trace);
    }
    @Test public void explicitHeadOnTheSameGroupBuilderSuppressesItsGetFallback() { // FR-APP-021 AC-2 AC-3; FR-HTTP-028 AC-3
        DefaultRestApp app=new DefaultRestApp();app.group("/api",group->group.route("/x").get((q,s)->fail("GET fallback ignored same-Route HEAD")).head((q,s)->s.header("X-Head","same-route").end()));
        RecordingExchange ex=dispatch(app,"HEAD","/api/x");assertEquals(200,ex.status);assertEquals(Collections.singletonList("same-route"),ex.committed("X-Head"));assertEquals(0,ex.body.size());
    }
    @Test public void laterSeparateGroupHeadDoesNotOverrideEarlierGetFallback() { // FR-APP-021 AC-1; FR-HTTP-028 AC-3
        DefaultRestApp app=new DefaultRestApp();app.group("/api",group->{group.get("/x",(q,s)->s.header("X-Route","get").send("get"));group.head("/x",(q,s)->fail("later separate HEAD displaced earlier GET"));});
        RecordingExchange ex=dispatch(app,"HEAD","/api/x");assertEquals(Collections.singletonList("get"),ex.committed("X-Route"));assertEquals(Collections.singletonList("3"),ex.committed("Content-Length"));assertEquals(0,ex.body.size());
    }
    @Test public void failedGroupConfigurationAndInvalidCallbackArrayPublishNothing() { // FR-APP-021 AC-3; FR-DX-009 AC-4
        DefaultRestApp app=new DefaultRestApp();IllegalStateException failure=new IllegalStateException("configuration failed");
        try{app.group("/api",group->{group.get("/partial",(q,s)->s.send("leak"));throw failure;});fail();}catch(IllegalStateException actual){assertSame(failure,actual);}
        try{app.group("/bad",group->{group.route("/partial").method("GET",(q,s,n)->s.send("leak"),(Middleware)null);});fail();}catch(NullPointerException expected){}
        app.freeze();RecordingExchange ex=new RecordingExchange("GET","/api/partial");app.dispatcher().handle(ex);assertEquals(404,ex.status);
        ex=new RecordingExchange("GET","/bad/partial");app.dispatcher().handle(ex);assertEquals(404,ex.status);
    }
    @Test public void groupMiddlewareDeclaredLateStillAppliesToNestedRoutesAndBuildersStayClosed() { // FR-DX-009 AC-2 AC-3 AC-4; FR-APP-021 AC-3
        DefaultRestApp app=new DefaultRestApp();List<String> trace=new ArrayList<>();AtomicReference<RouteBuilder> retained=new AtomicReference<>();app.group("/api",group->{
            group.group("/child",nested->{RouteBuilder route=nested.route("/x");retained.set(route);route.get((q,s)->{trace.add("route");s.send("ok");});nested.use((q,s,n)->{trace.add("child");n.run();});});
            group.use((q,s,n)->{trace.add("parent");n.run();});
        });
        try{retained.get().get((q,s,n)->n.run());fail("group builder escaped configuration lifetime");}catch(IllegalStateException expected){}
        assertEquals("ok",text(dispatch(app,"GET","/api/child/x")));assertEquals(Arrays.asList("parent","child","route"),trace);
    }
    @Test public void orderedStaticServicesNeverTakeTheRetainedWebSocketOnlyFootprint() throws Exception { // FR-APP-021 AC-6; FR-STATIC-002 AC-4; CON-APP-010 AC-1 AC-4
        File root=temporary.newFolder();Files.write(new File(root,"ws").toPath(),"static-secret".getBytes(StandardCharsets.UTF_8));
        DefaultRestApp app=new DefaultRestApp();app.staticFiles("/",root);app.ws("/ws",(q,s)->fail("ordinary HTTP upgraded"));app.freeze();
        for(String token:Arrays.asList("GET","HEAD","OPTIONS","COPY")) {
            RecordingExchange ex=new RecordingExchange(token,"/ws");app.dispatcher().handle(ex);
            assertEquals(token,token.equals("OPTIONS")?204:token.equals("COPY")?501:426,ex.status);assertFalse(text(ex).contains("static-secret"));
        }
    }
}
