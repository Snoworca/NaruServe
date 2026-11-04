package com.snoworca.naruserve.utils.auth;

/** A utils.auth class whose unlisted method reads the environment: the negative control of FR-DX-012 AC-1. */
public final class EnvFixture {

    private EnvFixture() {
    }

    static String fromEnvLike() {
        return String.valueOf(System.getenv("NARU_ENV"));
    }
}
