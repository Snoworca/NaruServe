package com.snoworca.naruserve.tinyhttpd;

import com.snoworca.naruserve.spi.StreamDrain;
import com.snoworca.naruserve.spi.UpgradeHandler;
import com.snoworca.tinyhttpd.http.Response;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.Map;

/**
 * File transfer, event stream and protocol upgrade over TinyHttpd, kept apart from the exchange bridge so later
 * waves fill these bodies without editing the adapter core.
 */
// @req FR-ENGINE-001
// @req FR-ENGINE-010
final class TinyHttpdFeatures {

    private TinyHttpdFeatures() {
    }

    /**
     * Commits through {@code ex} and copies exactly {@code length} bytes of {@code file} from {@code offset} with
     * plain buffered reads; the caller holds the exchange lock and has checked the range. The file is opened before
     * the commit, so a missing or unreadable file throws with the response still unstarted.
     */
    // @req FR-ENGINE-010
    static void sendFile(TinyHttpdExchange ex, int status, List<Map.Entry<String, String>> headers, File file,
            long offset, long length) throws IOException {
        OutputStream out;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            out = ex.commit(status, headers, length);
            byte[] buf = new byte[8192];
            for (long left = length; left > 0; ) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    throw new EOFException("file ended " + left + " bytes before the requested range: " + file);
                }
                out.write(buf, 0, n);
                left -= n;
            }
        }
        out.flush();
    }

    // @req FR-ENGINE-001
    static void eventStream(Response res, int status, List<Map.Entry<String, String>> headers, StreamDrain drain) {
        throw new UnsupportedOperationException("event streams on the TinyHttpd adapter arrive in wave 7");
    }

    // @req FR-ENGINE-001
    static void upgrade(Response res, List<Map.Entry<String, String>> headers, UpgradeHandler handler) {
        throw new UnsupportedOperationException("protocol upgrades on the TinyHttpd adapter arrive in wave 7");
    }
}
