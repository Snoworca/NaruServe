package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.BodyTooLargeException;
import com.snoworca.naruserve.HttpException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    // SDS-AC-12; IR-HTTP-011 AC-6, IR-HTTP-011 AC-3
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
            assertSame(BodyTooLargeException.class, e.getClass());
            assertEquals(10, e.limit());
        }
        assertEquals(10, returned);
        assertEquals(11, source.taken);
    }

    // SDS-AC-12; IR-HTTP-011 AC-6
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
            assertSame(BodyTooLargeException.class, expected.getClass());
            assertEquals(10, expected.limit());
            assertEquals(11, source.taken);
        }
    }

    // SDS-AC-12; IR-HTTP-011 AC-6, IR-HTTP-011 AC-1
    @Test
    public void sdsAc12_FR_HTTP_020_AC3_overLimitTypeIsADistinctIOException() {
        assertTrue(IOException.class.isAssignableFrom(BodyTooLargeException.class));
        assertEquals(IOException.class, BodyTooLargeException.class.getSuperclass());
        assertTrue(new BodyTooLargeException(10).getMessage().contains("10"));
        assertEquals("com.snoworca.naruserve.BodyTooLargeException", BodyTooLargeException.class.getName());
    }

    // SDS-AC-12; IR-HTTP-011 AC-6
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

    // SDS-AC-13; IR-HTTP-011 AC-7, IR-HTTP-011 AC-3
    @Test
    public void sdsAc13_FR_HTTP_020_AC4_declaredLengthOverLimitFailsFirstReadWithoutTouchingSource()
            throws IOException {
        CountingSource source = new CountingSource(11);
        InputStream in = new LimitedInputStream(source, 10, 11);
        try {
            in.read(new byte[16], 0, 16);
            fail("expected BodyTooLargeException");
        } catch (BodyTooLargeException expected) {
            assertSame(BodyTooLargeException.class, expected.getClass());
            assertEquals(10, expected.limit());
            assertEquals(0, source.taken);
        }
        CountingSource single = new CountingSource(11);
        try {
            new LimitedInputStream(single, 10, 11).read();
            fail("expected BodyTooLargeException");
        } catch (BodyTooLargeException expected) {
            assertSame(BodyTooLargeException.class, expected.getClass());
            assertEquals(0, single.taken);
        }
    }

    // IR-HTTP-011 AC-3: the urlencoded form reader answers both overruns 413 with the public class as the cause
    @Test
    public void IR_HTTP_011_AC3_formReaderAnswersBothOverruns413ThroughThePublicClass() throws IOException {
        byte[] eleven = "a=123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals(11, eleven.length);
        for (long declared : new long[] {-1, 11}) {
            try {
                FormBodyReader.read(new ByteArrayInputStream(eleven), declared, 10);
                fail("expected 413 for declared length " + declared);
            } catch (HttpException e) {
                assertEquals(413, e.status());
                assertSame(BodyTooLargeException.class, e.getCause().getClass());
                assertEquals(10, ((BodyTooLargeException) e.getCause()).limit());
            }
        }
        assertEquals("12345678", FormBodyReader.read(new ByteArrayInputStream("a=12345678".getBytes(
                StandardCharsets.US_ASCII)), -1, 10).get("a").get(0));
    }

    // IR-HTTP-011 AC-3: the multipart parser recognises a body overrun through the public class
    @Test
    public void IR_HTTP_011_AC3_multipartParserRecognisesTheOverrunThroughThePublicClass() throws IOException {
        String b = "XyZ";
        byte[] body = ("--" + b + "\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n"
                + "0123456789012345678901234567890123456789\r\n--" + b + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        MultipartSettings s = new MultipartSettings();
        s.maxRequestSize(64);
        assertTrue(body.length > 64);
        for (long declared : new long[] {-1, body.length}) {
            try {
                MultipartParser.parse(new ByteArrayInputStream(body), declared, b, s, 1048576L, new SpoolRegistry());
                fail("expected 413 for declared length " + declared);
            } catch (HttpException e) {
                assertEquals(413, e.status());
                assertSame(BodyTooLargeException.class, e.getCause().getClass());
                assertEquals(64, ((BodyTooLargeException) e.getCause()).limit());
            }
        }
    }

    // SDS-AC-12; FR-HTTP-020 AC-2 — a declared length within the limit ends the body there: no probe byte is taken
    // from the source, so a keep-alive stream's next request is never read.
    @Test
    public void sdsAc12_FR_HTTP_020_AC2_declaredLengthWithinLimitNeverReadsPastTheBody() throws IOException {
        CountingSource atLimit = new CountingSource(14);
        InputStream in = new LimitedInputStream(atLimit, 10, 10);
        byte[] buf = new byte[64];
        int total = 0;
        int n;
        while ((n = in.read(buf, total, buf.length - total)) > 0) {
            total += n;
        }
        assertEquals(10, total);
        assertEquals(-1, in.read());
        assertEquals(0, in.skip(5));
        assertEquals(10, atLimit.taken);

        CountingSource under = new CountingSource(14);
        InputStream small = new LimitedInputStream(under, 10, 5);
        for (int i = 0; i < 5; i++) {
            assertEquals(0, small.read());
        }
        assertEquals(-1, small.read());
        assertEquals(-1, small.read(buf, 0, 8));
        assertEquals(5, under.taken);
    }
}
