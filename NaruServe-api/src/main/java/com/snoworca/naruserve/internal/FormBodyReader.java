package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.HttpException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.util.List;
import java.util.Map;

/** Reads one {@code application/x-www-form-urlencoded} body under {@code formLimit}: 413 above it, 400 if malformed. */
// @req FR-MULTIPART-002
public final class FormBodyReader {

    private FormBodyReader() {
    }

    /**
     * Name to values in body order; a pair without '=' has the value "", an empty pair is skipped. {@code
     * contentLength} is the request Content-Length, or -1 when absent (chunked).
     */
    // @req FR-MULTIPART-002
    public static Map<String, List<String>> read(InputStream body, long contentLength, long formLimit)
            throws IOException {
        LimitedInputStream in = new LimitedInputStream(body, formLimit, contentLength);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            DefaultUploadFile.copy(in, buf);
        } catch (BodyTooLargeException e) {
            throw new HttpException(413, null, e);
        }
        String text;
        try {
            text = PercentDecoder.strictUtf8(buf.toByteArray());
        } catch (CharacterCodingException e) {
            throw new HttpException(400, null, e);
        }
        try {
            return DefaultRequest.parseQuery(text);
        } catch (IllegalArgumentException e) {
            throw new HttpException(400, "malformed form encoding", e);
        }
    }
}
