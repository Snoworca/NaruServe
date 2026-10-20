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

    // FR-APP-017 AC-8 — a rotation whose rename fails keeps the live file instead of truncating it
    @Test
    public void FR_APP_017_AC8_failedRotationRenameKeepsEarlierRecords() throws IOException {
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

    // FR-APP-017 AC-8 — a live-file rename that keeps failing deletes no further backup, is retried only after another
    // maxFileBytes, and is counted
    @Test
    public void FR_APP_017_AC8_persistentRenameFailureKeepsBackupsAndBacksOff() throws IOException {
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
            throw new ThreadDeath();
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

    // FR-APP-017 AC-4 — an Error in the write path drops that record and counts it; later records still reach the file
    @Test(timeout = 10000)
    public void FR_APP_017_AC4_writerSurvivesAnErrorInTheWritePath() throws Exception {
        File dir = tmp.newFolder("logs");
        java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
        RotatingFileLogger logger = track(new RotatingFileLogger(RotatingFileLogger.builder().directory(dir),
                (file, append) -> {
                    if (opens.getAndIncrement() == 0) {
                        throw new OutOfMemoryError("simulated");
                    }
                    return new FileOutputStream(file, append);
                }));
        logger.info("lost to the error");
        logger.flush();
        logger.info("survivor");
        logger.flush();
        assertTrue("the writer thread survived", logger.writer.isAlive());
        assertEquals(1, logger.droppedCount());
        String text = read(new File(dir, "naruserve.log"));
        assertTrue(text, text.contains("INFO survivor") && !text.contains("lost to the error"));
    }

    // FR-APP-017 AC-5 — flush() and close() give up after a bounded wait on a writer stuck inside a write
    @Test(timeout = 10000)
    public void FR_APP_017_AC5_flushAndCloseReturnWithinTheBoundOnAStalledWriter() throws Exception {
        File dir = tmp.newFolder("logs");
        BlockingStreams streams = new BlockingStreams();
        RotatingFileLogger.Builder b = RotatingFileLogger.builder().directory(dir).queueCapacity(2);
        b.waitMillis = 300;
        RotatingFileLogger logger = track(new RotatingFileLogger(b, streams));
        logger.info("blocker");
        assertTrue(streams.entered.await(5, TimeUnit.SECONDS));
        logger.info("queued");
        long start = System.nanoTime();
        logger.flush();
        logger.close();
        assertTrue("bounded", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        assertEquals("the queued record is counted as dropped", 1, logger.droppedCount());
        streams.release.countDown();
        logger.writer.join(5000);
        assertFalse("the writer stops once unblocked", logger.writer.isAlive());
    }

    // FR-APP-017 AC-8 — a failed backup shift stops the rotation instead of renaming the live file over naruserve.log.1
    @Test
    public void FR_APP_017_AC8_failedBackupShiftKeepsTheOldBackup() throws IOException {
        File dir = tmp.newFolder("logs");
        java.nio.file.Files.write(new File(dir, "naruserve.log.1").toPath(), "b1".getBytes(StandardCharsets.UTF_8));
        RotatingFileLogger.Builder b = RotatingFileLogger.builder().directory(dir).maxFileBytes(200).maxFiles(3);
        RotatingFileLogger logger = track(new RotatingFileLogger(b, new RotatingFileLogger.StreamFactory() {
            @Override
            public OutputStream open(File file, boolean append) throws IOException {
                return new FileOutputStream(file, append);
            }

            @Override
            public boolean rename(File from, File to) {
                return !from.getName().equals("naruserve.log.1") && from.renameTo(to);
            }
        }));
        int records = 8;
        for (int i = 0; i < records; i++) {
            logger.info("record " + i + " padding padding");
        }
        logger.close();
        assertEquals("b1", read(new File(dir, "naruserve.log.1")));
        assertEquals(records, lines(read(new File(dir, "naruserve.log"))));
        assertTrue(logger.rotationFailureCount() >= 1);
        assertEquals(0, logger.droppedCount());
    }

    /** Constant-pool scan of every main class (same rule as build.gradle scanClassRefs): Class names and L..; descriptors. */
    private static List<String> mainClassRefs(String... prefixes) throws Exception {
        File dir = new File(RotatingFileLogger.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue("main classes dir " + dir, dir.isDirectory());
        List<String> hits = new ArrayList<>();
        List<File> pending = new ArrayList<>(Arrays.asList(dir));
        while (!pending.isEmpty()) {
            File f = pending.remove(pending.size() - 1);
            File[] children = f.listFiles();
            if (children != null) {
                pending.addAll(Arrays.asList(children));
                continue;
            }
            if (!f.getName().endsWith(".class")) {
                continue;
            }
            try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(
                    new java.io.FileInputStream(f)))) {
                in.readInt();
                in.readUnsignedShort();
                in.readUnsignedShort();
                int count = in.readUnsignedShort();
                java.util.Map<Integer, String> utf8 = new java.util.HashMap<>();
                List<Integer> classNames = new ArrayList<>();
                for (int i = 1; i < count; i++) {
                    int tag = in.readUnsignedByte();
                    switch (tag) {
                        case 1: utf8.put(i, in.readUTF()); break;
                        case 7: classNames.add(in.readUnsignedShort()); break;
                        case 8: case 16: case 19: case 20: in.skipBytes(2); break;
                        case 15: in.skipBytes(3); break;
                        case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: in.skipBytes(4); break;
                        case 5: case 6: in.skipBytes(8); i++; break;
                        default: throw new AssertionError("unknown constant pool tag " + tag + " in " + f);
                    }
                }
                java.util.Set<String> refs = new java.util.TreeSet<>();
                for (int index : classNames) {
                    refs.add(utf8.get(index));
                }
                java.util.regex.Pattern descriptor = java.util.regex.Pattern.compile("L([^;<>()\\[\\]]+);");
                for (String text : utf8.values()) {
                    java.util.regex.Matcher m = descriptor.matcher(text);
                    while (m.find()) {
                        refs.add(m.group(1));
                    }
                }
                for (String ref : refs) {
                    for (String prefix : prefixes) {
                        if (ref.startsWith(prefix)) {
                            hits.add(f.getName() + " -> " + ref);
                        }
                    }
                }
            }
        }
        return hits;
    }

    // FR-APP-017 AC-6 — main classes reference no SLF4J or android.* type (animalsnifferMain: BuildChecksTest)
    @Test
    public void fr_APP_017_AC6_mainClassesReferenceNoSlf4jOrAndroidType() throws Exception {
        // Control: the same scan does find a type the logger really uses, so an empty result below means something.
        assertTrue(mainClassRefs("java/io/File").contains("RotatingFileLogger.class -> java/io/File"));
        assertEquals(new ArrayList<String>(), mainClassRefs("org/slf4j/", "android/"));
    }
}
