package com.snoworca.naruserve.internal;

import java.util.concurrent.ThreadLocalRandom;

/** Request id source: 64 random bits as 16 lowercase hex digits. */
// @req FR-APP-019
final class RequestIds {

    private RequestIds() {
    }

    // @req FR-APP-019
    static String next() {
        String hex = Long.toHexString(ThreadLocalRandom.current().nextLong());
        return "0000000000000000".substring(hex.length()) + hex;
    }
}
