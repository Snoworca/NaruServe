package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.HttpException;

/** A participating HTTP path capture failed the one strict UTF8 decoding pass. */
// @req FR-HTTP-027 AC-2; FR-APP-024 AC-3
public final class PathDecodeException extends HttpException {
    private static final long serialVersionUID=1L;
    PathDecodeException(String rawValue) { super(400,"Failed to decode param '"+rawValue+"'"); }
}
