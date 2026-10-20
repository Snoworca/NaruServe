package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.HttpException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The one streaming {@code multipart/form-data} parser of the core, shared by every adapter: no adapter, engine or
 * file-system API beyond {@code java.io}. Malformed bodies give 400; part count, part size, text total, body size
 * and part header limits give 413. A failed parse deletes every temp file it created.
 */
// @req FR-MULTIPART-001
// @req FR-MULTIPART-002
public final class MultipartParser {

    /** Bytes asked of the body per read. */
    static final int BUFFER = 8192;

    private MultipartParser() {
    }

    /**
     * The {@code boundary} parameter of {@code contentType}, name matched case-insensitively and a quoted-string
     * unquoted; null when missing, empty, over 70 chars, ending in a space or holding a char outside RFC 2046 bchars.
     */
    // @req FR-MULTIPART-001
    public static @Nullable String boundary(@Nullable String contentType) {
        if (contentType == null) {
            return null;
        }
        String b = params(contentType).get("boundary");
        if (b == null || b.isEmpty() || b.length() > 70 || b.charAt(b.length() - 1) == ' ') {
            return null;
        }
        for (int i = 0; i < b.length(); i++) {
            if (!bchar(b.charAt(i))) {
                return null;
            }
        }
        return b;
    }

    private static boolean bchar(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || "'()+_,-./:=? ".indexOf(c) >= 0;
    }

    /** {@link #parse(InputStream, long, String, MultipartSettings, long, SpoolRegistry)} without a Content-Length. */
    // @req FR-MULTIPART-001
    // @req FR-MULTIPART-002
    public static List<DefaultUploadFile> parse(InputStream body, String boundary, MultipartSettings s, long formLimit,
            SpoolRegistry spool) throws IOException {
        return parse(body, -1, boundary, s, formLimit, spool);
    }

    /**
     * Every part in body order. {@code contentLength} is the request Content-Length or -1; a declared length above
     * {@code maxRequestSize} fails on the first read, a chunked body once the bytes read pass it.
     */
    // @req FR-MULTIPART-001
    // @req FR-MULTIPART-002
    public static List<DefaultUploadFile> parse(InputStream body, long contentLength, String boundary,
            MultipartSettings s, long formLimit, SpoolRegistry spool) throws IOException {
        Scanner sc = new Scanner(new LimitedInputStream(body, s.maxRequestSize(), contentLength),
                ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1));
        List<DefaultUploadFile> parts = new ArrayList<>();
        SpoolingBuffer spooling = null;
        boolean ok = false;
        try {
            if (!sc.copyToDelimiter(null)) {
                throw new HttpException(400, "multipart body has no delimiter");
            }
            long textTotal = 0;
            while (!sc.afterDelimiterIsClose()) {
                if (parts.size() >= s.maxParts()) {
                    throw new HttpException(413, "more than " + s.maxParts() + " multipart parts");
                }
                Map<String, String> headers = sc.readHeaders();
                String disposition = headers.get("content-disposition");
                if (disposition == null || !mediaType(disposition).equals("form-data")) {
                    throw new HttpException(400, "multipart part without Content-Disposition: form-data");
                }
                Map<String, String> p = params(disposition);
                String name = p.get("name");
                if (name == null) {
                    throw new HttpException(400, "multipart part without a name");
                }
                String filename = p.get("filename");
                String contentType = headers.get("content-type");
                if (filename != null) {
                    SpoolingBuffer buf = new SpoolingBuffer(s.memoryThreshold(), s.tempDirectory(), spool);
                    spooling = buf;
                    copyPart(sc, buf, s.maxFileSize(), "maxFileSize");
                    parts.add(buf.toUpload(name, filename, contentType));
                    spooling = null;
                } else {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    long size = copyPart(sc, buf, formLimit - textTotal, "formLimit");
                    textTotal += size;
                    parts.add(new DefaultUploadFile(name, null, contentType, size, buf.toByteArray(), null, spool));
                }
            }
            // Epilogue content is ignored, but its bytes still belong to the bounded request body.
            sc.discardRemainder();
            ok = true;
            return parts;
        } catch (BodyTooLargeException e) {
            throw new HttpException(413, null, e);
        } finally {
            if (!ok) {
                if (spooling != null) {
                    spooling.discard();
                }
                for (DefaultUploadFile u : parts) {
                    u.release();
                }
            }
        }
    }

    /** Copies one part's content into {@code out}; 413 on the first byte above {@code limit}, 400 if truncated. */
    private static long copyPart(Scanner sc, OutputStream out, long limit, String limitName) throws IOException {
        long[] count = {0};
        Sink sink = (b, off, len) -> {
            if (count[0] + len > limit) {
                throw new HttpException(413, "multipart part exceeds " + limitName);
            }
            count[0] += len;
            out.write(b, off, len);
        };
        if (!sc.copyToDelimiter(sink)) {
            throw new HttpException(400, "multipart body ends before the close delimiter");
        }
        return count[0];
    }

    /** The media type before ';', trimmed and lower-cased. */
    static String mediaType(String value) {
        int semi = value.indexOf(';');
        return (semi < 0 ? value : value.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The parameters after the first ';', names lower-cased, first occurrence kept, quoted-strings unquoted with
     * only {@code \"} and {@code \\} unescaped.
     */
    static Map<String, String> params(String value) {
        Map<String, String> m = new LinkedHashMap<>();
        int n = value.length();
        int i = value.indexOf(';');
        if (i < 0) {
            return m;
        }
        while (i < n) {
            while (i < n && (value.charAt(i) == ';' || value.charAt(i) == ' ' || value.charAt(i) == '\t')) {
                i++;
            }
            int start = i;
            while (i < n && value.charAt(i) != '=' && value.charAt(i) != ';') {
                i++;
            }
            String name = value.substring(start, i).trim().toLowerCase(Locale.ROOT);
            String v = null;
            if (i < n && value.charAt(i) == '=') {
                i++;
                while (i < n && (value.charAt(i) == ' ' || value.charAt(i) == '\t')) {
                    i++;
                }
                if (i < n && value.charAt(i) == '"') {
                    StringBuilder sb = new StringBuilder();
                    i++;
                    boolean closed = false;
                    while (i < n) {
                        char c = value.charAt(i++);
                        // Browsers send a filename's backslash raw: only \" and \\ are escapes, others stay literal.
                        if (c == '\\' && i < n && (value.charAt(i) == '"' || value.charAt(i) == '\\')) {
                            sb.append(value.charAt(i++));
                        } else if (c == '"') {
                            closed = true;
                            break;
                        } else {
                            sb.append(c);
                        }
                    }
                    v = closed ? sb.toString() : null;
                    while (i < n && value.charAt(i) != ';') {
                        i++;
                    }
                } else {
                    int vs = i;
                    while (i < n && value.charAt(i) != ';') {
                        i++;
                    }
                    v = value.substring(vs, i).trim();
                }
            }
            if (!name.isEmpty() && v != null && !m.containsKey(name)) {
                m.put(name, v);
            }
        }
        return m;
    }

    @FunctionalInterface
    private interface Sink {
        void write(byte[] b, int off, int len) throws IOException;
    }

    /** A read buffer over the body; the stream is taken to start with a CRLF so a leading delimiter matches. */
    private static final class Scanner {
        private final InputStream in;
        private final byte[] delim;
        private final byte[] buf;
        private int pos;
        private int lim;
        private boolean eof;

        Scanner(InputStream in, byte[] delim) {
            this.in = in;
            this.delim = delim;
            this.buf = new byte[BUFFER + delim.length];
            buf[0] = '\r';
            buf[1] = '\n';
            lim = 2;
        }

        /** Compacts and reads up to one BUFFER; false at end of body. */
        private boolean fill() throws IOException {
            if (eof) {
                return false;
            }
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, lim - pos);
                lim -= pos;
                pos = 0;
            }
            int n = in.read(buf, lim, Math.min(BUFFER, buf.length - lim));
            if (n < 0) {
                eof = true;
                return false;
            }
            lim += n;
            return true;
        }

        private int next() throws IOException {
            while (pos == lim) {
                if (!fill()) {
                    return -1;
                }
            }
            return buf[pos++] & 0xff;
        }

        private int peek() throws IOException {
            while (pos == lim) {
                if (!fill()) {
                    return -1;
                }
            }
            return buf[pos] & 0xff;
        }

        /** Hands every byte before the next delimiter to {@code sink} (null drops them); false if none follows. */
        boolean copyToDelimiter(@Nullable Sink sink) throws IOException {
            while (true) {
                int at = indexOfDelim();
                if (at >= 0) {
                    if (sink != null) {
                        sink.write(buf, pos, at - pos);
                    }
                    pos = at + delim.length;
                    return true;
                }
                int safe = lim - (delim.length - 1);
                if (safe > pos) {
                    if (sink != null) {
                        sink.write(buf, pos, safe - pos);
                    }
                    pos = safe;
                }
                if (!fill()) {
                    return false;
                }
            }
        }

        /** Discards buffered and remaining framed bytes, keeping the whole-body limit active. */
        void discardRemainder() throws IOException {
            do {
                pos = lim;
            } while (fill());
        }

        private int indexOfDelim() {
            outer:
            for (int i = pos; i <= lim - delim.length; i++) {
                for (int j = 0; j < delim.length; j++) {
                    if (buf[i + j] != delim[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }

        /** After a delimiter: true for the close delimiter "--", false for padding then CRLF; else 400. */
        boolean afterDelimiterIsClose() throws IOException {
            int c = next();
            if (c == '-') {
                if (next() == '-') {
                    return true; // parse discards the epilogue through the same bounded body stream
                }
                throw malformed();
            }
            while (c == ' ' || c == '\t') {
                c = next();
            }
            if (c != '\r' || next() != '\n') {
                throw malformed();
            }
            return false;
        }

        /**
         * One part's header lines up to the empty line; names lower-cased, first occurrence kept. A line counts its
         * bytes without the CRLF: above 8192 gives 413, and so does a total above 16384.
         */
        Map<String, String> readHeaders() throws IOException {
            Map<String, String> headers = new LinkedHashMap<>();
            int total = 0;
            while (true) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int len = 0;
                while (true) {
                    int c = next();
                    if (c < 0) {
                        throw malformed();
                    }
                    if (c == '\r' && peek() == '\n') {
                        next();
                        break;
                    }
                    if (++len > MultipartSettings.MAX_HEADER_LINE) {
                        throw new HttpException(413, "multipart part header line too long");
                    }
                    line.write(c);
                }
                if (len == 0) {
                    return headers;
                }
                total += len;
                if (total > MultipartSettings.MAX_HEADER_TOTAL) {
                    throw new HttpException(413, "multipart part headers too large");
                }
                String h = new String(line.toByteArray(), StandardCharsets.UTF_8);
                int colon = h.indexOf(':');
                if (colon > 0) {
                    String name = h.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    if (!headers.containsKey(name)) {
                        headers.put(name, h.substring(colon + 1).trim());
                    }
                }
            }
        }

        private static HttpException malformed() {
            return new HttpException(400, "malformed multipart body");
        }
    }
}
