package com.snoworca.naruserve.filelog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.LogLevel;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-4-9.sds.md. */
public class RotatingFileLoggerTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private final List<RotatingFileLogger> loggers = new ArrayList<>();

    @After
    public void closeAll() {
        for (RotatingFileLogger l : loggers) {
            l.close();
        }
    }

    private RotatingFileLogger track(RotatingFileLogger logger) {
        loggers.add(logger);
        return logger;
    }

    private static String read(File file) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static int lines(String text) {
        return text.isEmpty() ? 0 : text.split("\n").length;
    }

    // SDS-AC-6; FR-APP-017 AC-1
    @Test
    public void sdsAc6_FR_APP_017_AC1_defaultsAndValuesBelowOneAreRefused() {
        RotatingFileLogger.Builder b = RotatingFileLogger.builder();
        assertEquals(new File("./logs"), b.directory);
        assertEquals(5242880L, b.maxFileBytes);
        assertEquals(5, b.maxFiles);
        assertEquals(1024, b.queueCapacity);
        assertEquals(LogLevel.INFO, b.level);
        for (Runnable r : Arrays.<Runnable>asList(() -> b.maxFileBytes(0), () -> b.maxFiles(0),
                () -> b.queueCapacity(0), () -> b.maxFileBytes(-1))) {
            try {
                r.run();
                fail("value below 1 accepted");
            } catch (IllegalArgumentException expected) {
                // refused by the setter
            }
        }
    }

    // SDS-AC-7; FR-APP-017 AC-2
    @Test
    public void sdsAc7_FR_APP_017_AC2_rotationKeepsAtMostMaxFilesOfAtMostMaxFileBytes() throws IOException {
        File dir = tmp.newFolder("logs");
        RotatingFileLogger logger = track(RotatingFileLogger.builder().directory(dir).maxFileBytes(200).maxFiles(3)
                .build());
        for (int i = 0; i < 50; i++) {
            logger.info("record " + i + " padding padding padding");
        }
        logger.close();
        String[] names = dir.list();
        Arrays.sort(names);
        assertTrue(Arrays.toString(names), names.length <= 3);
        List<String> allowed = Arrays.asList("naruserve.log", "naruserve.log.1", "naruserve.log.2");
        for (String name : names) {
            assertTrue(name, allowed.contains(name));
            assertTrue(name, new File(dir, name).length() <= 200);
        }
        String current = read(new File(dir, "naruserve.log"));
        assertTrue(current, current.contains(" INFO record 49 padding padding padding"));
        assertTrue(current, current.matches("(?s)\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4} INFO .*"));
        assertTrue(read(new File(dir, "naruserve.log.1")).contains("record 4"));
    }

    /** Streams whose first write signals {@code entered} and waits on {@code release}. */
    private static final class BlockingStreams implements RotatingFileLogger.StreamFactory {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public OutputStream open(File file, boolean append) throws IOException {
            return new FilterOutputStream(new FileOutputStream(file, append)) {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    out.write(b, off, len);
                }
            };
        }
    }

    // SDS-AC-8, SDS-AC-11; FR-APP-017 AC-3, FR-APP-017 AC-7
    @Test
    public void sdsAc8_FR_APP_017_AC3_fullQueueDropsTheThirdAndLaterRecordsWithoutBlocking() throws Exception {
        File dir = tmp.newFolder("logs");
        BlockingStreams streams = new BlockingStreams();
        RotatingFileLogger.Builder b = RotatingFileLogger.builder().directory(dir).queueCapacity(2);
        RotatingFileLogger logger = track(new RotatingFileLogger(b, streams));
        logger.info("blocker");
        assertTrue(streams.entered.await(5, TimeUnit.SECONDS));
        long start = System.nanoTime();
        logger.info("a");
        logger.info("b");
        logger.info("c");
        logger.info("d");
        assertTrue("callers never block", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
        assertEquals(2, logger.droppedCount());
        streams.release.countDown();
        logger.close();
        String text = read(new File(dir, "naruserve.log"));
        assertEquals(text, 3, lines(text));
        assertTrue(text.contains("INFO a") && text.contains("INFO b") && !text.contains("INFO c"));
    }

    // SDS-AC-9; FR-APP-017 AC-4
    @Test
    public void sdsAc9_FR_APP_017_AC4_uncreatableDirectoryDropsAndCountsWithoutThrowing() throws IOException {
        File notADir = tmp.newFile("plain-file");
        RotatingFileLogger logger = track(RotatingFileLogger.builder().directory(new File(notADir, "logs")).build());
        logger.info("one");
        logger.warn("two");
        logger.error("three", new IllegalStateException("x"));
        logger.flush();
        assertEquals(3, logger.droppedCount());
        logger.close();
    }

    // SDS-AC-10; FR-APP-017 AC-5
    @Test
    public void sdsAc10_FR_APP_017_AC5_flushWritesAllAndCloseStopsTheWriterAndCountsLaterCalls() throws IOException {
        File dir = tmp.newFolder("logs");
        RotatingFileLogger logger = track(RotatingFileLogger.builder().directory(dir).build());
        for (int i = 0; i < 100; i++) {
            logger.info("line " + i);
        }
        logger.debug("below the INFO threshold");
        logger.flush();
        String text = read(new File(dir, "naruserve.log"));
        assertEquals(100, lines(text));
        assertFalse(text.contains("below the INFO threshold"));
        assertTrue(logger.writer.isAlive());
        logger.close();
        assertFalse("no writer thread remains", logger.writer.isAlive());
        long before = logger.droppedCount();
        logger.info("after close");
        logger.flush();
        logger.close();
        assertEquals(before + 1, logger.droppedCount());
        assertFalse(read(new File(dir, "naruserve.log")).contains("after close"));
    }

    // SDS-AC-10; FR-APP-017 AC-5 — a stack trace follows its line
    @Test
    public void sdsAc10_FR_APP_017_AC5_errorWithThrowableWritesTheStackTrace() throws IOException {
        File dir = tmp.newFolder("logs");
        RotatingFileLogger logger = track(RotatingFileLogger.builder().directory(dir).level(LogLevel.TRACE).build());
        logger.trace("t");
        logger.error("failed", new IllegalStateException("boom"));
        logger.close();
        String text = read(new File(dir, "naruserve.log"));
        assertTrue(text, text.contains(" TRACE t\n"));
        assertTrue(text, text.contains(" ERROR failed\njava.lang.IllegalStateException: boom\n"));
    }

    // FR-APP-017 AC-2 — a rotation whose rename fails keeps the live file instead of truncating it
    @Test
    public void FR_APP_017_AC2_failedRotationRenameKeepsEarlierRecords() throws IOException {
        File dir = tmp.newFolder("logs");
        File blocker = new File(dir, "naruserve.log.1");
        assertTrue(blocker.mkdir());
        assertTrue(new File(blocker, "keep").createNewFile());
        RotatingFileLogger logger = track(RotatingFileLogger.builder().directory(dir).maxFileBytes(60).maxFiles(2)
                .build());
        logger.info("first record");
        logger.flush();
        logger.info("second record padding padding padding");
        logger.close();
        String text = read(new File(dir, "naruserve.log"));
        assertTrue(text, text.contains("INFO first record"));
        assertTrue(text, text.contains("INFO second record"));
    }

    // FR-APP-017 AC-2 — a live-file rename that keeps failing deletes no further backup, is retried only after another
    // maxFileBytes, and is counted
    @Test
    public void FR_APP_017_AC2_persistentRenameFailureKeepsBackupsAndBacksOff() throws IOException {
        File dir = tmp.newFolder("logs");
        java.nio.file.Files.write(new File(dir, "naruserve.log.1").toPath(), "b1".getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.write(new File(dir, "naruserve.log.2").toPath(), "b2".getBytes(StandardCharsets.UTF_8));
        RotatingFileLogger.Builder b = RotatingFileLogger.builder().directory(dir).maxFileBytes(200).maxFiles(3);
        RotatingFileLogger logger = track(new RotatingFileLogger(b, new RotatingFileLogger.StreamFactory() {
            @Override
            public OutputStream open(File file, boolean append) throws IOException {
                return new FileOutputStream(file, append);
            }

            @Override
            public boolean rename(File from, File to) {
                return !from.getName().equals("naruserve.log") && from.renameTo(to);
            }
        }));
        int records = 12;
        for (int i = 0; i < records; i++) {
            logger.info("record " + i + " padding padding");
        }
        logger.close();
        String live = read(new File(dir, "naruserve.log"));
        assertEquals(live, records, lines(live));
        assertEquals("b1", read(new File(dir, "naruserve.log.2")));
        long failures = logger.rotationFailureCount();
        assertTrue("failures " + failures, failures >= 1 && failures <= records / 3);
        assertEquals(0, logger.droppedCount());
    }

    // FR-APP-017 AC-4 — a Throwable whose toString throws is dropped and counted, never thrown to the caller
    @Test
    public void FR_APP_017_AC4_throwingThrowableIsDroppedNotThrown() throws IOException {
        File dir = tmp.newFolder("logs");
        RotatingFileLogger logger = track(RotatingFileLogger.builder().directory(dir).build());
        Throwable bad = new Throwable() {
            @Override
            public String toString() {
                throw new IllegalStateException("toString failed");
            }
        };
        logger.error("x", bad);
        logger.flush();
        assertEquals(1, logger.droppedCount());
    }

    // FR-APP-017 AC-5 — close() returns even when the writer died and the queue is full
    @Test(timeout = 10000)
    public void FR_APP_017_AC5_closeReturnsWhenTheWriterDiedWithAFullQueue() throws Exception {
        File dir = tmp.newFolder("logs");
        RotatingFileLogger.Builder b = RotatingFileLogger.builder().directory(dir).queueCapacity(1);
        RotatingFileLogger logger = new RotatingFileLogger(b, (file, append) -> {
            throw new AssertionError("writer dies");
        });
        logger.info("kills the writer");
        logger.writer.join(5000);
        assertFalse(logger.writer.isAlive());
        logger.info("fills the queue");
        Thread closer = new Thread(logger::close);
        closer.setDaemon(true);
        closer.start();
        closer.join(3000);
        assertFalse("close() hung on a dead writer", closer.isAlive());
    }
}
