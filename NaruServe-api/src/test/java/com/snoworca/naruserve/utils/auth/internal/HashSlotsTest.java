package com.snoworca.naruserve.utils.auth.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.utils.auth.AuthUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** The shared hash slot pool (REL-TOKEN-001 component; the HTTP and builder ACs come with the hashing endpoints). */
public class HashSlotsTest {

    // REL-TOKEN-001 component: max(1, cores / 2) slots by default and a queue of 4 times the slots
    @Test(timeout = 10000)
    public void defaultSlotCountAndQueue() {
        assertEquals(4, HashSlots.defaultSlots(8));
        assertEquals(1, HashSlots.defaultSlots(1));
        assertEquals(1, HashSlots.defaultSlots(2));
        assertEquals(1, HashSlots.defaultSlots(0));
        HashSlots two = new HashSlots(2);
        try {
            assertEquals(2, two.slots());
            assertEquals(8, two.queueCapacity());
            assertEquals(5000L, two.waitMillis());
        } finally {
            two.shutdown();
        }
    }

    /** Collects one async outcome. */
    private static final class Outcome implements HashSlots.Callback<String> {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> value = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void done(String v, Throwable e) {
            value.set(v);
            error.set(e);
            done.countDown();
        }
    }

    // REL-TOKEN-001 component: with the slot held and the queue full, the next request is refused without hashing
    @Test(timeout = 10000)
    public void fullQueueRefusesImmediatelyWithoutRunningTheWork() throws Exception {
        HashSlots slots = new HashSlots(1, 4, 5000);
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicInteger ran = new AtomicInteger();
        try {
            Outcome holder = new Outcome();
            slots.submit(() -> {
                started.countDown();
                hold.await();
                return "held";
            }, holder);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            List<Outcome> queued = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Outcome o = new Outcome();
                slots.submit(() -> {
                    ran.incrementAndGet();
                    return "queued";
                }, o);
                queued.add(o);
            }
            Outcome refused = new Outcome();
            long before = System.nanoTime();
            slots.submit(() -> {
                ran.incrementAndGet();
                return "sixth";
            }, refused);
            // the refusal is answered inside submit(): no wait for a slot, whatever the clock says
            assertEquals("refused before submit returned", 0, refused.done.getCount());
            assertTrue(refused.done.await(1, TimeUnit.SECONDS));
            // a sanity bound only (the blocking alternative waits for the slot forever)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 10_000);
            assertTrue(String.valueOf(refused.error.get()), refused.error.get() instanceof AuthUnavailableException);
            assertNull(refused.value.get());
            try {
                slots.call(() -> "sync");
                fail("a synchronous caller entered a full queue");
            } catch (AuthUnavailableException expected) {
                assertEquals("SERVICE_UNAVAILABLE", expected.getCode());
            }
            hold.countDown();
            assertTrue(holder.done.await(5, TimeUnit.SECONDS));
            for (Outcome o : queued) {
                assertTrue(o.done.await(5, TimeUnit.SECONDS));
                assertEquals("queued", o.value.get());
            }
            assertEquals(4, ran.get());
        } finally {
            hold.countDown();
            slots.shutdown();
        }
    }

    // REL-TOKEN-001 component: a waiter that gets no slot within the wait cap fails and its work never runs
    @Test(timeout = 10000)
    public void waitCapFailsTheWaiterAndSkipsItsWork() throws Exception {
        HashSlots slots = new HashSlots(1, 4, 200);
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicInteger ran = new AtomicInteger();
        try {
            slots.submit(() -> {
                started.countDown();
                hold.await();
                return "held";
            }, new Outcome());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Outcome waiter = new Outcome();
            long before = System.nanoTime();
            slots.submit(() -> {
                ran.incrementAndGet();
                return "late";
            }, waiter);
            assertTrue(waiter.done.await(5, TimeUnit.SECONDS));
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
            assertTrue("waited " + waited, waited >= 150);
            assertTrue(String.valueOf(waiter.error.get()), waiter.error.get() instanceof AuthUnavailableException);
            hold.countDown();
            // the slot frees; the timed-out work must still not run, and the next caller gets a slot
            assertEquals("next", slots.call(() -> "next"));
            assertEquals(0, ran.get());
        } finally {
            hold.countDown();
            slots.shutdown();
        }
    }

    // REL-TOKEN-001 component: never more computations at once than slots; errors of the work reach the caller
    @Test(timeout = 20000)
    public void concurrencyNeverExceedsTheSlots() throws Exception {
        final HashSlots slots = new HashSlots(2, 64, 5000);
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        try {
            List<Thread> callers = new ArrayList<>();
            final AtomicInteger failures = new AtomicInteger();
            for (int i = 0; i < 16; i++) {
                Thread t = new Thread(() -> {
                    try {
                        slots.call(() -> {
                            int now = running.incrementAndGet();
                            synchronized (peak) {
                                peak.set(Math.max(peak.get(), now));
                            }
                            Thread.sleep(20);
                            running.decrementAndGet();
                            return "h";
                        });
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                });
                callers.add(t);
                t.start();
            }
            for (Thread t : callers) {
                t.join();
            }
            assertEquals(0, failures.get());
            assertTrue("peak " + peak.get(), peak.get() <= 2);
            final IllegalStateException boom = new IllegalStateException("hash failed");
            try {
                slots.call(() -> {
                    throw boom;
                });
                fail("work error lost");
            } catch (IllegalStateException e) {
                assertEquals(boom, e);
            }
        } finally {
            slots.shutdown();
        }
    }

    // REL-TOKEN-001 component: shutdown fails every waiter (sync and async) and later requests are refused
    @Test(timeout = 30000)
    public void shutdownFailsWaitersAndRefusesLaterRequests() throws Exception {
        final HashSlots slots = new HashSlots(1, 4, 60000);
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        try {
            slots.submit(() -> {
                started.countDown();
                hold.await();
                return "held";
            }, new Outcome());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Outcome queued = new Outcome();
            slots.submit(() -> "queued", queued);
            final AtomicReference<Throwable> syncError = new AtomicReference<>();
            Thread sync = new Thread(() -> {
                try {
                    slots.call(() -> "sync");
                } catch (Throwable e) {
                    syncError.set(e);
                }
            });
            sync.start();
            // the synchronous caller is queued (it asked for a slot) and parked on its answer before the shutdown
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((slots.requested() < 3 || sync.getState() != Thread.State.WAITING) && sync.isAlive()
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals("held, queued and the synchronous call asked for a slot", 3, slots.requested());
            assertEquals("the synchronous caller waits in the queue", Thread.State.WAITING, sync.getState());
            slots.shutdown();
            sync.join(5000);
            assertFalse("a synchronous waiter is still blocked", sync.isAlive());
            assertTrue(String.valueOf(syncError.get()), syncError.get() instanceof AuthUnavailableException);
            assertTrue(queued.done.await(5, TimeUnit.SECONDS));
            assertTrue(String.valueOf(queued.error.get()), queued.error.get() instanceof AuthUnavailableException);
            Outcome late = new Outcome();
            slots.submit(() -> "late", late);
            assertTrue(late.done.await(1, TimeUnit.SECONDS));
            assertTrue(late.error.get() instanceof AuthUnavailableException);
        } finally {
            hold.countDown();
            slots.shutdown(); // idempotent: frees the hash and timer threads when an assertion above failed
        }
    }

    // REL-TOKEN-001 component: a call made inside hashing work reuses its slot instead of waiting for another
    @Test(timeout = 10000)
    public void nestedCallReusesTheSlot() throws Exception {
        final HashSlots slots = new HashSlots(1, 0, 500);
        try {
            assertEquals("outer:inner", slots.call(() -> "outer:" + slots.call(() -> "inner")));
        } finally {
            slots.shutdown();
        }
    }

    // REL-TOKEN-001 component: slot refusals carry Retry-After 1 second, a store failure does not
    @Test(timeout = 10000)
    public void slotRefusalsCarryRetryAfter() throws Exception {
        HashSlots slots = new HashSlots(1, 0, 5000);
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        try {
            slots.submit(() -> {
                started.countDown();
                hold.await();
                return "held";
            }, new Outcome());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            try {
                slots.call(() -> "x");
                fail("admitted past the cap");
            } catch (AuthUnavailableException e) {
                assertEquals(Long.valueOf(1), e.getRetryAfterSeconds());
            }
            assertNull(new AuthUnavailableException("store down").getRetryAfterSeconds());
        } finally {
            hold.countDown();
            slots.shutdown();
        }
    }
}
