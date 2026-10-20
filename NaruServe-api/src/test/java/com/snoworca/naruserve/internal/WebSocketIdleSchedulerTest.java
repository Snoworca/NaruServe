package com.snoworca.naruserve.internal;

import static com.snoworca.naruserve.internal.WebSocketSenderTest.LOG;
import static com.snoworca.naruserve.internal.WebSocketSenderTest.session;
import static com.snoworca.naruserve.internal.WebSocketSenderTest.threadsNamed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.internal.WebSocketSenderTest.HoldingChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** The idle scheduler. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-6.sds.md. */
public class WebSocketIdleSchedulerTest {

    private static final String IDLE_CLOSE = "close 1001 idle timeout";

    private static WebSocketUpgrade upgrade(int port, long idleMillis) {
        DefaultWebSocketOptions o = new DefaultWebSocketOptions();
        o.idleTimeout(idleMillis, TimeUnit.MILLISECONDS);
        return new WebSocketUpgrade(o, null, LOG, new WebSocketSender(128, () -> port, LOG),
                new WebSocketIdleScheduler(() -> port, LOG));
    }

    private static long ms(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    // SDS-AC-10; FR-WS-008 AC-6: a text message received or sent every 500 ms keeps a 1 s idle timeout from firing
    // for 3 s; the session is closed with 1001 once the messages stop
    @Test(timeout = 30000)
    public void sdsAc10_FR_WS_008_AC6_textEvery500msFor3sKeepsTheSessionOpen() throws Exception {
        WebSocketUpgrade up = upgrade(5001, 1000);
        DefaultWebSocketSession received = session(up);
        DefaultWebSocketSession sent = session(up);
        HoldingChannel chr = new HoldingChannel();
        HoldingChannel chs = new HoldingChannel();
        received.onOpen(chr);
        sent.onOpen(chs);
        for (int i = 0; i < 6; i++) {
            Thread.sleep(500);
            received.onText("in " + i);
            sent.sendText("out " + i);
        }
        assertTrue(received.isOpen());
        assertTrue(sent.isOpen());
        assertTrue(!chr.started.contains(IDLE_CLOSE) && !chs.started.contains(IDLE_CLOSE));
        // once the messages stop the 1 s timeout fires
        for (int i = 0; i < 2; i++) {
            String call;
            HoldingChannel ch = i == 0 ? chr : chs;
            do {
                call = ch.started.poll(5, TimeUnit.SECONDS);
            } while (call != null && !IDLE_CLOSE.equals(call));
            assertEquals(IDLE_CLOSE, call);
        }
    }

    // SDS-AC-10; FR-WS-008 AC-6: pings are no activity, so a session that only pings still idles out
    @Test(timeout = 30000)
    public void sdsAc10_FR_WS_008_AC6_pingsDoNotResetTheIdleClock() throws Exception {
        WebSocketUpgrade up = upgrade(5002, 1000);
        DefaultWebSocketSession s = session(up);
        HoldingChannel ch = new HoldingChannel();
        long opened = System.nanoTime();
        s.onOpen(ch);
        long closedAt = 0;
        for (int i = 0; i < 20 && closedAt == 0; i++) {
            s.ping();
            Thread.sleep(150);
            if (ch.started.contains(IDLE_CLOSE)) {
                closedAt = System.nanoTime();
            }
        }
        assertTrue("no idle close though only pings were sent", closedAt != 0);
        assertTrue("closed after " + ms(closedAt - opened) + " ms", ms(closedAt - opened) < 2500);
    }

    // SDS-AC-11; FR-WS-008 AC-4: check closes once nanoTime - lastActivityNanos reaches the timeout and reschedules
    // at the remaining time before that
    @Test(timeout = 30000)
    public void sdsAc11_FR_WS_008_AC4_checkClosesWith1001OnlyOnceIdleForTheTimeout() throws Exception {
        WebSocketUpgrade up = upgrade(5003, 1000);
        DefaultWebSocketSession s = session(up);
        HoldingChannel ch = new HoldingChannel();
        s.onOpen(ch);
        long last = s.lastActivityNanos();
        Thread.sleep(400);
        s.touch();
        assertTrue(s.lastActivityNanos() - last >= TimeUnit.MILLISECONDS.toNanos(400));
        long touched = s.lastActivityNanos();
        String call = ch.started.poll(5, TimeUnit.SECONDS);
        long at = System.nanoTime();
        assertEquals(IDLE_CLOSE, call);
        assertTrue("closed " + ms(at - touched) + " ms after the last activity", at - touched
                >= TimeUnit.MILLISECONDS.toNanos(1000));
        assertTrue("rescheduled late: closed " + ms(at - touched) + " ms after the last activity",
                ms(at - touched) <= 1200);
        up.idle().check(s);
        assertNull("a second close was handed over", ch.started.poll(300, TimeUnit.MILLISECONDS));
    }

    // SDS-AC-12; FR-WS-008 AC-5: with one session's close held, the scheduler still checks another session on time,
    // aborts the held one 1 s after handing over its close, writes nothing itself, and its one thread is a daemon
    // named naruserve-<port>-ws-idle that exists only once something was scheduled
    @Test(timeout = 30000)
    public void sdsAc12_FR_WS_008_AC5_aHeldCloseNeverDelaysOtherChecksAndIsAbortedAfterOneSecond()
            throws Exception {
        assertTrue(threadsNamed("naruserve-5004-ws-idle").isEmpty());
        int[] port = {5104};
        DefaultWebSocketOptions o = new DefaultWebSocketOptions();
        o.idleTimeout(1, TimeUnit.SECONDS);
        WebSocketUpgrade up = new WebSocketUpgrade(o, null, LOG, new WebSocketSender(128, () -> port[0], LOG),
                new WebSocketIdleScheduler(() -> port[0], LOG));
        port[0] = 5004; // the port is read when the thread is made, not when the scheduler is built
        assertTrue("the thread is made on the first schedule", threadsNamed("naruserve-5004-ws-idle").isEmpty());
        DefaultWebSocketSession held = session(up);
        DefaultWebSocketSession other = session(up);
        HoldingChannel chh = new HoldingChannel();
        HoldingChannel cho = new HoldingChannel();
        CountDownLatch release = chh.hold("close");
        held.onOpen(chh);
        assertEquals(1, threadsNamed("naruserve-5004-ws-idle").size());
        Thread.sleep(300);
        other.onOpen(cho);
        long otherDue = other.lastActivityNanos() + TimeUnit.SECONDS.toNanos(1);
        try {
            assertEquals(IDLE_CLOSE, chh.started.poll(5, TimeUnit.SECONDS));
            long handed = chh.firstNanos.get("close");
            assertEquals(IDLE_CLOSE, cho.calls.poll(5, TimeUnit.SECONDS));
            long otherLate = ms(cho.firstNanos.get("close") - otherDue);
            assertTrue("the other session's check ran " + otherLate + " ms late", otherLate <= 100);
            assertEquals("abort", chh.started.poll(5, TimeUnit.SECONDS));
            long abortAfter = ms(chh.firstNanos.get("abort") - handed);
            assertTrue("abort " + abortAfter + " ms after the close was handed over",
                    abortAfter >= 900 && abortAfter <= 1200);
            Thread idle = chh.firstThread.get("abort");
            assertEquals("naruserve-5004-ws-idle", idle.getName());
            assertTrue(idle.isDaemon());
            assertNotEquals(idle, chh.firstThread.get("close"));
            assertNotEquals(idle, cho.firstThread.get("close"));
            for (Thread t : cho.threads) {
                assertNotEquals("the scheduler thread wrote to a channel", idle, t);
            }
            assertEquals(1, threadsNamed("naruserve-5004-ws-idle").size());
        } finally {
            release.countDown();
        }
    }

    // SDS-AC-12; FR-WS-008 AC-5: a session whose close returned is not aborted, and onClose stops its checks
    @Test(timeout = 30000)
    public void sdsAc12_FR_WS_008_AC5_aReturnedCloseIsNotAbortedAndOnCloseUnwatches() throws Exception {
        WebSocketUpgrade up = upgrade(5005, 1000);
        DefaultWebSocketSession idled = session(up);
        DefaultWebSocketSession closed = session(up);
        HoldingChannel chi = new HoldingChannel();
        HoldingChannel chc = new HoldingChannel();
        idled.onOpen(chi);
        closed.onOpen(chc);
        closed.onClose(1000, "");
        assertEquals(IDLE_CLOSE, chi.calls.poll(5, TimeUnit.SECONDS));
        assertEquals(IDLE_CLOSE, chi.started.poll(5, TimeUnit.SECONDS));
        assertNull("aborted though the close returned", chi.started.poll(1500, TimeUnit.MILLISECONDS));
        assertNull("a closed session was still checked", chc.started.poll(10, TimeUnit.MILLISECONDS));
    }

    // SDS-AC-3 of docs/sds/2026-10-19.naruserve.naruserve-v1-wave-7-13.sds.md; PERF-APP-001 AC-3: the idle scheduler
    // thread, made on the first schedule, is made with a requested stack of 262144 bytes
    @Test(timeout = 20000)
    public void wave7s13SdsAc3_PERF_APP_001_AC3_theIdleThreadRequestsA256KiBStack() throws Exception {
        WebSocketIdleScheduler idle = new WebSocketIdleScheduler(() -> 5013, LOG);
        WebSocketUpgrade up = new WebSocketUpgrade(new DefaultWebSocketOptions(), null, LOG,
                new WebSocketSender(128, () -> 5013, LOG), idle);
        assertEquals("no thread made yet", 0L, idle.requestedStack());
        DefaultWebSocketSession s = session(up);
        s.onOpen(new HoldingChannel());
        try {
            assertEquals(262144L, idle.requestedStack());
        } finally {
            s.onClose(1000, "");
        }
    }
}
