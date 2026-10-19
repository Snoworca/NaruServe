package com.snoworca.naruserve.gson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Strictness;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;
import com.snoworca.naruserve.spi.JsonCodec;
import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import org.jspecify.annotations.Nullable;

/**
 * {@link JsonCodec} over Gson 2.14.0. Every decode reads through a {@link JsonReader} with the nesting limit
 * {@code maxDepth} (default 64, also for a caller-built Gson), requires the end of the document after the value and
 * reports malformed input as {@link IOException}. A type error such as a class without a no-arg constructor stays
 * an unchecked Gson exception.
 */
// @req FR-JSON-003
public final class GsonJsonCodec implements JsonCodec {

    private static final int DEFAULT_MAX_DEPTH = 64;

    private final Gson gson;
    private final int maxDepth;

    /** Uses {@link #defaultGson()} and nesting limit 64. */
    // @req FR-JSON-003
    public GsonJsonCodec() {
        this(defaultGson(), DEFAULT_MAX_DEPTH);
    }

    /** Uses {@code gson} with nesting limit 64. */
    // @req FR-JSON-003
    public GsonJsonCodec(Gson gson) {
        this(gson, DEFAULT_MAX_DEPTH);
    }

    /** Uses {@code gson} with nesting limit {@code maxDepth}, {@code 1..255}. */
    // @req FR-JSON-003
    public GsonJsonCodec(Gson gson, int maxDepth) {
        if (gson == null) {
            throw new NullPointerException("gson");
        }
        if (maxDepth < 1 || maxDepth > 255) {
            throw new IllegalArgumentException("maxDepth must be 1..255: " + maxDepth);
        }
        this.gson = gson;
        this.maxDepth = maxDepth;
    }

    /** A new Gson with {@link Strictness#STRICT} and {@code disableJdkUnsafe()}. */
    // @req FR-JSON-003
    public static Gson defaultGson() {
        return new GsonBuilder().setStrictness(Strictness.STRICT).disableJdkUnsafe().create();
    }

    // @req FR-JSON-003
    @Override
    public <T> @Nullable T decode(Reader reader, Type type) throws IOException {
        JsonReader in = gson.newJsonReader(reader);
        in.setNestingLimit(maxDepth);
        // newJsonReader gives LEGACY_STRICT when the Gson set no strictness; tighten that to STRICT.
        if (in.getStrictness() == Strictness.LEGACY_STRICT) {
            in.setStrictness(Strictness.STRICT);
        }
        try {
            @SuppressWarnings("unchecked")
            T value = (T) gson.getAdapter(TypeToken.get(type)).read(in);
            if (in.peek() != JsonToken.END_DOCUMENT) {
                // Reached only with a lenient caller Gson; a strict reader's peek() already threw.
                throw new MalformedJsonException("trailing data after the JSON value");
            }
            return value;
        } catch (MalformedJsonException | JsonSyntaxException e) {
            throw new IOException(e.getMessage(), e);
        } catch (IllegalStateException | NumberFormatException | EOFException e) {
            // "Expected X but was Y", a non-number for a floating-point target, or truncated input: wrong or
            // malformed input that Gson.fromJson also reports as JsonSyntaxException.
            throw new IOException(e.getMessage(), new JsonSyntaxException(e));
        }
    }

    // @req FR-JSON-003
    @Override
    public void encode(@Nullable Object value, Writer writer) throws IOException {
        try {
            gson.toJson(value, writer);
        } catch (JsonIOException e) {
            // toJson wraps a writer failure; give the SPI's IOException back.
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw e;
        }
    }
}
