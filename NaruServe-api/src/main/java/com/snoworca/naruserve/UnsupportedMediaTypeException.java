package com.snoworca.naruserve;

import org.jspecify.annotations.Nullable;

/** {@code 415 Unsupported Media Type}. */
// @req IR-APP-003
public class UnsupportedMediaTypeException extends HttpException {

    private static final long serialVersionUID = 1L;

    public UnsupportedMediaTypeException() {
        super(415);
    }

    public UnsupportedMediaTypeException(@Nullable String message) {
        super(415, message);
    }
}
