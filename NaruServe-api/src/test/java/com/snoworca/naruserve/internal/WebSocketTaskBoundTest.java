package com.snoworca.naruserve.internal;

import static org.junit.Assert.*;
import com.snoworca.naruserve.spi.WebSocketChannel;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class WebSocketTaskBoundTest {
    // FR-WS-007 AC-1 AC-6: 1024 includes the write in flight, independently of payload bytes.
    @Test public void emptyTextTasksAreBoundedAndCloseReplacesTheQueue() throws Exception { verify("text"); }
    @Test public void emptyBinaryTasksAreBoundedAndCloseReplacesTheQueue() throws Exception { verify("binary"); }
    @Test public void pingTasksAreBoundedAndCloseReplacesTheQueue() throws Exception { verify("ping"); }

    private static final class GateChannel implements WebSocketChannel {
        final CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), closed=new CountDownLatch(1);
        final AtomicInteger writes=new AtomicInteger();
        volatile boolean open=true; volatile int code;
        public void sendText(String text) throws java.io.IOException {
            if (text.equals("x")) {
                entered.countDown();
                try {release.await();} catch (InterruptedException e) {Thread.currentThread().interrupt();throw new java.io.IOException(e);}
            }
            writes.incrementAndGet();
        }
        public void sendBinary(byte[] data) {writes.incrementAndGet();}
        public void ping(byte[] data) {writes.incrementAndGet();}
        public void close(int code,String reason) {this.code=code;open=false;closed.countDown();}
        public void abort() {open=false;release.countDown();}
        public boolean isOpen() {return open;}
    }

    private static void verify(String kind) throws Exception {
        WebSocketUpgrade upgrade=WebSocketSenderTest.upgrade(new DefaultWebSocketOptions());
        DefaultWebSocketSession session=WebSocketSenderTest.session(upgrade);
        GateChannel channel=new GateChannel();
        session.onOpen(channel);
        try {
            session.sendText("x");
            assertTrue(channel.entered.await(5,TimeUnit.SECONDS));
            for(int i=0;i<1023;i++) sendEmpty(session,kind);
            assertTrue("1024 pending tasks should be accepted",session.isOpen());
            assertEquals("payload counter unchanged",1,upgrade.sender().queuedBytes(session));
            assertEquals(1023,queuedTasks(upgrade.sender(),session));
            sendEmpty(session,kind);
            assertFalse("1025th task must overflow",session.isOpen());
            assertEquals("close replaces all pending messages",1,queuedTasks(upgrade.sender(),session));
            sendEmpty(session,kind);
            assertEquals(1,queuedTasks(upgrade.sender(),session));
            channel.release.countDown();
            assertTrue(channel.closed.await(5,TimeUnit.SECONDS));
            assertEquals(1008,channel.code);
            assertEquals("discarded empty tasks were not written",1,channel.writes.get());
            assertTrue(WebSocketSenderTest.eventually(()->upgrade.sender().idle(session)));
            assertEquals(0,upgrade.sender().queuedBytes(session));
        } finally {
            channel.release.countDown();session.onClose(1000,"");upgrade.registry().terminate();
        }
    }
    private static void sendEmpty(DefaultWebSocketSession session,String kind) {
        if(kind.equals("text"))session.sendText("");
        else if(kind.equals("binary"))session.sendBinary(new byte[0]);
        else session.ping();
    }
    private static int queuedTasks(WebSocketSender sender,DefaultWebSocketSession session)throws Exception {
        Field slots=WebSocketSender.class.getDeclaredField("slots");slots.setAccessible(true);
        Object slot=((Map<?,?>)slots.get(sender)).get(session);
        Field queued=slot.getClass().getDeclaredField("queued");queued.setAccessible(true);
        return ((Collection<?>)queued.get(slot)).size();
    }
}
