package com.snoworca.naruserve.filelog;

import com.snoworca.naruserve.LogLevel;
import com.snoworca.naruserve.Logger;
import java.io.File;
import java.io.FileOutputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * File {@link Logger}: records at or above the level go through a bounded queue to one writer thread that appends
 * {@code <timestamp> <LEVEL> <message>} lines to {@code naruserve.log} and rotates to {@code naruserve.log.1} ...
 * {@code naruserve.log.<maxFiles-1>}. A full queue, a failed write or a closed logger drops the record and counts it
 * in {@link #droppedCount()}; no logging call blocks or throws.
 */
// @req FR-APP-017
@NullMarked
public final class RotatingFileLogger implements Logger, Flushable, AutoCloseable {

    static final String FILE_NAME = "naruserve.log";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Object STOP = new Object();

    /** Opens the file output stream; the test hook that lets tests block the writer. */
    // @req FR-APP-017
    interface StreamFactory {
        OutputStream open(File file, boolean append) throws IOException;
    }

    final File directory;
    final long maxFileBytes;
    final int maxFiles;
    final LogLevel level;
    final Thread writer;
    private final StreamFactory streams;
    private final BlockingQueue<Object> queue;
    private final AtomicLong dropped = new AtomicLong();
    // Guards closed against log(): no record enters the queue after the STOP marker.
    private final Object lock = new Object();
    private boolean closed;

    // Writer thread only.
    private @Nullable OutputStream out;
    private long size;

    /** Defaults: directory {@code ./logs}, 5242880 bytes per file, 5 files, 1024 queued records, level INFO. */
    // @req FR-APP-017
    public static Builder builder() {
        return new Builder();
    }

    // @req FR-APP-017
    RotatingFileLogger(Builder b, StreamFactory streams) {
        this.directory = b.directory;
        this.maxFileBytes = b.maxFileBytes;
        this.maxFiles = b.maxFiles;
        this.level = b.level;
        this.streams = Objects.requireNonNull(streams, "streams");
        this.queue = new ArrayBlockingQueue<>(b.queueCapacity);
        this.writer = new Thread(this::drain, "naruserve-filelog-writer");
        writer.setDaemon(true);
        writer.start();
    }

    @Override
    public void trace(String message) {
        log(LogLevel.TRACE, message, null);
    }

    @Override
    public void debug(String message) {
        log(LogLevel.DEBUG, message, null);
    }

    @Override
    public void info(String message) {
        log(LogLevel.INFO, message, null);
    }

    @Override
    public void warn(String message) {
        log(LogLevel.WARN, message, null);
    }

    @Override
    public void error(String message) {
        log(LogLevel.ERROR, message, null);
    }

    @Override
    public void error(String message, @Nullable Throwable error) {
        log(LogLevel.ERROR, message, error);
    }

    private void log(LogLevel at, String message, @Nullable Throwable error) {
        if (at.compareTo(level) < 0) {
            return;
        }
        String line;
        try {
            line = format(at, message, error);
        } catch (RuntimeException e) {
            // A Throwable whose toString or printStackTrace throws must not reach the caller.
            dropped.incrementAndGet();
            return;
        }
        synchronized (lock) {
            if (closed || !queue.offer(line)) {
                dropped.incrementAndGet();
            }
        }
    }

    /** The IR-APP-006 console line, with the stack trace on the following lines. */
    private static String format(LogLevel at, String message, @Nullable Throwable error) {
        StringBuilder sb = new StringBuilder()
                .append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date()))
                .append(' ').append(at.name()).append(' ').append(message).append('\n');
        if (error != null) {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace, true));
            sb.append(trace.toString().replace(System.getProperty("line.separator"), "\n"));
        }
        return sb.toString();
    }

    /** Records dropped by a full queue, a failed write or a closed logger. */
    // @req FR-APP-017
    public long droppedCount() {
        return dropped.get();
    }

    /** Returns once every record queued before the call is written (or dropped); at once after close. */
    // @req FR-APP-017
    @Override
    public void flush() {
        CountDownLatch marker = new CountDownLatch(1);
        synchronized (lock) {
            if (closed) {
                return;
            }
        }
        try {
            // A concurrent close() may stop the writer before it takes or reaches the marker.
            while (!queue.offer(marker, 100, TimeUnit.MILLISECONDS)) {
                if (!writer.isAlive()) {
                    return;
                }
            }
            while (!marker.await(100, TimeUnit.MILLISECONDS)) {
                if (!writer.isAlive()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Writes the queued records, closes the file and joins the writer thread; idempotent. */
    // @req FR-APP-017
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        boolean interrupted = false;
        // A writer that died from an Error never frees queue space; stop waiting once it is gone.
        while (writer.isAlive()) {
            try {
                if (queue.offer(STOP, 100, TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        while (writer.isAlive()) {
            try {
                writer.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // @req FR-APP-017
    private void drain() {
        while (true) {
            Object item;
            try {
                item = queue.take();
            } catch (InterruptedException e) {
                // Only close() ends the writer; an interrupt from elsewhere is ignored.
                continue;
            }
            if (item == STOP) {
                closeStream();
                return;
            }
            if (item instanceof CountDownLatch) {
                ((CountDownLatch) item).countDown();
            } else {
                write((String) item);
            }
        }
    }

    private void write(String record) {
        byte[] bytes = record.getBytes(UTF_8);
        try {
            if (out == null) {
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("cannot create " + directory);
                }
                File file = new File(directory, FILE_NAME);
                size = file.length();
                out = streams.open(file, true);
            }
            if (size > 0 && size + bytes.length > maxFileBytes) {
                closeStream();
                File file = new File(directory, FILE_NAME);
                // A failed rename keeps the live file: append to it rather than truncate earlier records.
                boolean movedAway = rotate();
                out = streams.open(file, !movedAway);
                size = movedAway ? 0 : file.length();
            }
            out.write(bytes, 0, bytes.length);
            out.flush();
            size += bytes.length;
        } catch (IOException | RuntimeException e) {
            dropped.incrementAndGet();
            closeStream();
        }
    }

    /**
     * {@code naruserve.log} becomes {@code .1}, {@code .n} becomes {@code .n+1}; the oldest is deleted. Returns whether
     * {@code naruserve.log} was moved or deleted, so the caller may start it empty.
     */
    // @req FR-APP-017
    boolean rotate() {
        File oldest = new File(directory, FILE_NAME + "." + (maxFiles - 1));
        if (maxFiles == 1) {
            oldest = new File(directory, FILE_NAME);
        }
        boolean deleted = oldest.delete();
        for (int n = maxFiles - 2; n >= 1; n--) {
            File from = new File(directory, FILE_NAME + "." + n);
            if (from.exists()) {
                from.renameTo(new File(directory, FILE_NAME + "." + (n + 1)));
            }
        }
        if (maxFiles > 1) {
            return new File(directory, FILE_NAME).renameTo(new File(directory, FILE_NAME + ".1"));
        }
        return deleted;
    }

    private void closeStream() {
        OutputStream o = out;
        out = null;
        if (o != null) {
            try {
                o.close();
            } catch (IOException ignored) {
                // nothing left to write to
            }
        }
    }

    /** Settings of a {@link RotatingFileLogger}; values below 1 throw {@link IllegalArgumentException}. */
    // @req FR-APP-017
    public static final class Builder {
        File directory = new File("./logs");
        long maxFileBytes = 5242880L;
        int maxFiles = 5;
        int queueCapacity = 1024;
        LogLevel level = LogLevel.INFO;

        private Builder() {
        }

        public Builder directory(File directory) {
            this.directory = Objects.requireNonNull(directory, "directory");
            return this;
        }

        // @req FR-APP-017
        public Builder maxFileBytes(long maxFileBytes) {
            this.maxFileBytes = atLeastOne("maxFileBytes", maxFileBytes);
            return this;
        }

        // @req FR-APP-017
        public Builder maxFiles(int maxFiles) {
            this.maxFiles = (int) atLeastOne("maxFiles", maxFiles);
            return this;
        }

        // @req FR-APP-017
        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = (int) atLeastOne("queueCapacity", queueCapacity);
            return this;
        }

        public Builder level(LogLevel level) {
            this.level = Objects.requireNonNull(level, "level");
            return this;
        }

        /** A new logger with its own writer thread. */
        // @req FR-APP-017
        public RotatingFileLogger build() {
            return new RotatingFileLogger(this, FileOutputStream::new);
        }

        private static long atLeastOne(String name, long value) {
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be at least 1, was " + value);
            }
            return value;
        }
    }
}
