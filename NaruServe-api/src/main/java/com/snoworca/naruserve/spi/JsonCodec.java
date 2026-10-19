package com.snoworca.naruserve.spi;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import org.jspecify.annotations.Nullable;

/** JSON binding SPI; the Gson implementation lives in NaruServe-gson. Uses java.* types only. */
// @req IR-APP-001
public interface JsonCodec {

    /** Reads one JSON value of {@code type} from {@code reader}. */
    <T> T decode(Reader reader, Type type) throws IOException;

    /** Writes {@code value} (JSON {@code null} when null) to {@code writer}. */
    void encode(@Nullable Object value, Writer writer) throws IOException;
}
