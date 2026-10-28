package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.*;
import com.snoworca.naruserve.testing.*;
import org.junit.Test;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static com.snoworca.naruserve.internal.HttpExecutionContractTest.text;

/** A completed request may continue while its app is alive; terminal app cancellation closes that capability. */
public class HttpCompletedContinuationShutdownTest {
    static final class Owned implements AutoCloseable { final AtomicInteger closes=new AtomicInteger();public void close(){closes.incrementAndGet();} }
    @Test public void normallyCompletedSavedNextCannotEnterCallbacksAfterTheApplicationTerminates() { // FR-APP-023 AC-3 AC-5; CON-APP-010 AC-3
        SettingsCapturingAdapter adapter=new SettingsCapturingAdapter();RecordingLogger logger=new RecordingLogger();
        DefaultRestApp app=new DefaultRestApp(adapter,new StubJsonCodec(),logger);Owned owned=new Owned();AtomicReference<Next> saved=new AtomicReference<>();AtomicInteger normal=new AtomicInteger(),errors=new AtomicInteger();
        app.onStart(context->context.register(Owned.class,owned));
        app.get("/x",(request,response,next)->{saved.set(next);response.send("ok");});
        app.use((request,response,next)->normal.incrementAndGet());
        app.use((failure,request,response,next)->errors.incrementAndGet());app.listen(0);
        LifecycleExchange exchange=new LifecycleExchange("GET","/x");adapter.handler.handle(exchange);
        assertEquals("ok",text(exchange.recording));assertEquals(1,exchange.input.closes.get());assertEquals(0,normal.get());
        app.shutdown(0,TimeUnit.SECONDS);assertEquals(AppState.TERMINATED,app.state());assertEquals(1,owned.closes.get());int records=logger.lines.size();
        saved.get().run();saved.get().error(new IllegalStateException("late"));saved.get().route();saved.get().router();
        assertEquals("saved continuation resurrected a cleaned execution",0,normal.get());assertEquals(0,errors.get());
        assertEquals(records,logger.lines.size());assertEquals(1,logger.info().size());assertEquals(1,exchange.recording.commits.get());assertEquals(1,exchange.recording.finishes.get());assertEquals(0,exchange.recording.aborts.get());assertEquals(1,exchange.input.closes.get());assertEquals(1,owned.closes.get());
    }
    @Test public void savedNextAfterResponseStillRunsWhileTheApplicationIsRunning() { // FR-APP-022 AC-3; FR-APP-023 AC-4 AC-5
        SettingsCapturingAdapter adapter=new SettingsCapturingAdapter();RecordingLogger logger=new RecordingLogger();DefaultRestApp app=new DefaultRestApp(adapter,new StubJsonCodec(),logger);
        Owned owned=new Owned();AtomicReference<Next> saved=new AtomicReference<>();AtomicInteger callbacks=new AtomicInteger();app.onStart(context->context.register(Owned.class,owned));
        app.get("/x",(request,response,next)->{saved.set(next);response.send("ok");});
        app.use((request,response,next)->{assertSame(owned,request.service(Owned.class));assertEquals(0,owned.closes.get());assertTrue(app.lifecycle().onRequestThread());callbacks.incrementAndGet();});app.listen(0);
        try {
            LifecycleExchange exchange=new LifecycleExchange("GET","/x");adapter.handler.handle(exchange);assertEquals(1,exchange.input.closes.get());assertEquals(AppState.RUNNING,app.state());
            saved.get().run();assertEquals(1,callbacks.get());assertEquals("ok",text(exchange.recording));assertEquals(1,exchange.recording.commits.get());assertEquals(1,exchange.input.closes.get());assertEquals(1,logger.info().size());assertFalse(app.lifecycle().onRequestThread());
        } finally {app.shutdown(0,TimeUnit.SECONDS);}
        assertEquals(1,owned.closes.get());
    }
}
