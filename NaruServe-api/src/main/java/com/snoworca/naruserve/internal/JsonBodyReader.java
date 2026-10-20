package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.common.MediaTypes;
import com.snoworca.naruserve.UnsupportedMediaTypeException;
import com.snoworca.naruserve.spi.JsonCodec;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.lang.reflect.Type;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * Reads one JSON request body: media type (415), declared and streamed size (413), empty body and malformed input
 * or UTF-8 (400), then the app codec. An {@link HttpException} in a failure's cause chain is rethrown as is.
 */
// @req FR-JSON-001
final class JsonBodyReader {

    /** The default JSON body limit, 1 MiB, as Express {@code express.json}. */
    static final long DEFAULT_LIMIT = 1048576L;
    /** The largest {@code app.jsonLimit}: 1 GiB. */
    static final long MAX_LIMIT = 1073741824L;

    private JsonBodyReader() {
    }

    /** The decoded body of {@code req}; the caller owns the body through its {@link BodyState}. */
    // @req FR-JSON-001
    static <T> @Nullable T read(DefaultRequest req, Type type, JsonCodec codec, long limit) {
        if (!isJsonType(req.header("Content-Type"))) {
            throw new UnsupportedMediaTypeException();
        }
        long declared = declaredLength(req.header("Content-Length"));
        if (declared > limit) {
            throw new HttpException(413);
        }
        LimitedInputStream in = new LimitedInputStream(req.bodyStream(), limit, declared);
        // REPORT, not the InputStreamReader default REPLACE: invalid UTF-8 must fail, not decode to U+FFFD.
        PushbackReader reader = new PushbackReader(new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)), 1);
        try {
            int c;
            do {
                c = reader.read();
            } while (c == ' ' || c == '\t' || c == '\n' || c == '\r');
            if (c < 0) {
                throw new HttpException(400); // Gson returns null for empty input; an empty body is not JSON null
            }
            reader.unread(c);
            T value = codec.decode(reader, type);
            if (in.overrun()) {
                throw new HttpException(413); // the codec swallowed the limit failure: the value is truncated
            }
            return value;
        } catch (IOException e) {
            throw mapped(e, in, true);
        } catch (RuntimeException e) {
            throw mapped(e, in, false);
        }
    }

    /**
     * 413 when the stream overran; else an HttpException in the cause chain; else 400 for an IOException or a
     * decoding failure anywhere in the chain; else the unchecked failure itself, which the error path answers 500.
     */
    private static RuntimeException mapped(Exception e, LimitedInputStream in, boolean checked) {
        if (in.overrun()) {
            return new HttpException(413, null, e);
        }
        boolean malformedText = false;
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < 64; t = t.getCause()) { // the bound stops a cyclic chain
            if (t instanceof HttpException) {
                return (HttpException) t;
            }
            malformedText |= t instanceof CharacterCodingException;
        }
        if (checked || malformedText) {
            return new HttpException(400, null, e);
        }
        return (RuntimeException) e;
    }

    private static long declaredLength(@Nullable String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1; // the adapter frames the body; a bad header never reaches a handler
        }
    }

    /**
     * {@code application/json} or {@code application/<subtype>+json}, case-insensitive, with no {@code charset}
     * parameter or {@code charset=utf-8}.
     */
    // @req FR-JSON-001
    static boolean isJsonType(@Nullable String contentType) {
        return MediaTypes.isJsonUtf8(contentType);
    }
}
