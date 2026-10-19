package com.snoworca.naruserve.testing;

import com.snoworca.naruserve.spi.JsonCodec;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Minimal {@link JsonCodec} for the harness, with no Gson dependency: encodes null, numbers and booleans as their
 * JSON literal; anything else, and decoding, throws {@link UnsupportedOperationException}.
 */
// @req FR-APP-009
@NullMarked
public final class StubJsonCodec implements JsonCodec {

    // @req FR-APP-009
    @Override
    public <T> T decode(Reader reader, Type type) throws IOException {
        throw new UnsupportedOperationException("StubJsonCodec does not decode; use NaruServe-gson");
    }

    // @req FR-APP-009
    @Override
    public void encode(@Nullable Object value, Writer writer) throws IOException {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            writer.write(String.valueOf(value));
        } else {
            throw new UnsupportedOperationException("StubJsonCodec encodes only null, numbers and booleans");
        }
    }
}
