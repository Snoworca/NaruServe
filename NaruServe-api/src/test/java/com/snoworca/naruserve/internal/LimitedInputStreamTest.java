package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

public class LimitedInputStreamTest {

    /** A real byte source that counts how many bytes callers took from it. */
    private static final class CountingSource extends ByteArrayInputStream {
        int taken;

        CountingSource(int size) {
            super(new byte[size]);
        }

        @Override
        public synchronized int read() {
            int r = super.read();
            if (r >= 0) {
                taken++;
            }
            return r;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            int n = super.read(b, off, len);
            if (n > 0) {
                taken += n;
            }
            return n;
        }
    }

    // SDS-AC-12; FR-HTTP-020 AC-1
    @Test
    public void sdsAc12_FR_HTTP_020_AC1_zeroOrNegativeLimitIsRefused() {
        for (long limit : new long[] {0, -1}) {
            try {
                new LimitedInputStream(new CountingSource(1), limit, -1);
                fail("expected IllegalArgumentException for " + limit);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }

    // SDS-AC-12; FR-HTTP-020 AC-2
    @Test
    public void sdsAc12_FR_HTTP_020_AC2_sourceOfExactlyLimitBytesReadsToEnd() throws IOException {
        InputStream in = new LimitedInputStream(new CountingSource(10), 10, -1);
        byte[] buf = new byte[64];
        int total = 0;
        int n;
        while ((n = in.read(buf, total, buf.length - total)) > 0) {
            total += n;
        }
        assertEquals(10, total);
        assertEquals(-1, n);
        assertEquals(-1, in.read());
    }

    // SDS-AC-12; FR-HTTP-020 AC-2
    @Test
    public void sdsAc12_FR_HTTP_020_AC2_declaredLengthEqualToLimitIsAccepted() throws IOException {
        InputStream in = new LimitedInputStream(new CountingSource(10), 10, 10);
        for (int i = 0; i < 10; i++) {
            assertEquals(0, in.read());
        }
        assertEquals(-1, in.read());
    }

    // SDS-AC-12; FR-HTTP-020 AC-3
    @Test
    public void sdsAc12_FR_HTTP_020_AC3_bulkReadsReturnTenBytesThenThrowAfterTakingEleven() throws IOException {
        CountingSource source = new CountingSource(100);
        InputStream in = new LimitedInputStream(source, 10, -1);
        byte[] buf = new byte[64];
        int returned = 0;
        try {
            int n;
            while ((n = in.read(buf, returned, buf.length - returned)) > 0) {
                returned += n;
            }
            fail("expected BodyTooLargeException");
        } catch (BodyTooLargeException e) {
            assertEquals(10, e.limit());
        }
        assertEquals(10, returned);
        assertEquals(11, source.taken);
    }

    // SDS-AC-12; FR-HTTP-020 AC-3
    @Test
    public void sdsAc12_FR_HTTP_020_AC3_singleByteReadsReturnTenThenThrow() throws IOException {
        CountingSource source = new CountingSource(11);
        InputStream in = new LimitedInputStream(source, 10, -1);
        for (int i = 0; i < 10; i++) {
            assertEquals(0, in.read());
        }
        try {
            in.read();
            fail("expected BodyTooLargeException");
        } catch (BodyTooLargeException expected) {
            assertEquals(11, source.taken);
        }
    }

    // SDS-AC-12; FR-HTTP-020 AC-3
    @Test
    public void sdsAc12_FR_HTTP_020_AC3_overLimitTypeIsADistinctIOException() {
        assertTrue(IOException.class.isAssignableFrom(BodyTooLargeException.class));
        assertEquals(IOException.class, BodyTooLargeException.class.getSuperclass());
        assertTrue(new BodyTooLargeException(10).getMessage().contains("10"));
    }

    // SDS-AC-12; FR-HTTP-020 AC-3
    @Test
    public void sdsAc12_FR_HTTP_020_AC3_skipCountsTowardTheLimit() throws IOException {
        InputStream in = new LimitedInputStream(new CountingSource(11), 10, -1);
        try {
            long skipped = 0;
            while (skipped < 11) {
                long s = in.skip(11 - skipped);
                if (s <= 0) {
                    break;
                }
                skipped += s;
            }
            in.read();
            fail("expected BodyTooLargeException");
        } catch (BodyTooLargeException expected) {
            // skip cannot bypass the limit
        }
    }

    // SDS-AC-13; FR-HTTP-020 AC-4
    @Test
    public void sdsAc13_FR_HTTP_020_AC4_declaredLengthOverLimitFailsFirstReadWithoutTouchingSource()
            throws IOException {
        CountingSource source = new CountingSource(11);
        InputStream in = new LimitedInputStream(source, 10, 11);
        try {
            in.read(new byte[16], 0, 16);
            fail("expected BodyTooLargeException");
        } catch (BodyTooLargeException expected) {
            assertEquals(0, source.taken);
        }
        CountingSource single = new CountingSource(11);
        try {
            new LimitedInputStream(single, 10, 11).read();
            fail("expected BodyTooLargeException");
        } catch (BodyTooLargeException expected) {
            assertEquals(0, single.taken);
        }
    }
}
