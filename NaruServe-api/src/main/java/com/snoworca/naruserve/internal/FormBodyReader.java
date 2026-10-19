package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.HttpException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
        byte[] b = new byte[8192];
        try {
            int n;
            while ((n = in.read(b, 0, b.length)) >= 0) {
                buf.write(b, 0, n);
            }
        } catch (BodyTooLargeException e) {
            throw new HttpException(413, null, e);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buf.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new HttpException(400, null, e);
        }
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String pair : text.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            List<String> values = map.get(name);
            if (values == null) {
                values = new ArrayList<>();
                map.put(name, values);
            }
            values.add(value);
        }
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            e.setValue(Collections.unmodifiableList(e.getValue()));
        }
        return Collections.unmodifiableMap(map);
    }

    private static String decode(String s) {
        String d = PercentDecoder.decode(s, true);
        if (d == null) {
            throw new HttpException(400, "malformed form encoding");
        }
        return d;
    }
}
