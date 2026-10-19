package com.snoworca.naruserve;

/** Thrown when a request body is read after it was already read, parsed or closed. */
// @req FR-HTTP-019
public class BodyAlreadyConsumedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // @req FR-HTTP-019
    public BodyAlreadyConsumedException(String message) {
        super(message);
    }
}
