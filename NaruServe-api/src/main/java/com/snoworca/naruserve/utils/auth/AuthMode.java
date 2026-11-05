package com.snoworca.naruserve.utils.auth;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * How strict auth runs. {@link #PROD} is the default. {@link #DEV} relaxes exactly eight things: a temporary key for a
 * missing secret; the secure-transport check; the hash-cost and key-strength failures, which become warnings; a
 * {@code .user(...)} password that breaks the password policy, which creates the account with a warning; a
 * password-less admin {@code .user(...)}, which gets a temporary password written to the log; a file store without a
 * file key, which starts with a warning and stores plain data; legacy {@code trustProxy(true)}, which is no doctor
 * failure; and no final auth error layer, so an app exception no earlier layer answered goes on to Core's page with its
 * stack trace. Authorization is never relaxed. The app passes the mode in code, usually
 * {@code .mode(AuthMode.fromEnv())}.
 */
// @req SEC-AUTH-002
public enum AuthMode {
    /** Local development; see the class description for the closed list of relaxations. */
    DEV,
    /** Production; the default. */
    PROD;

    /** The environment variable {@link #fromEnv()} reads. */
    public static final String ENV_VARIABLE = "NARU_ENV";

    /**
     * {@link #DEV} when the environment variable {@code NARU_ENV} is {@code dev} (any letter case), otherwise {@link #PROD}:
     * unset, empty, {@code prod} or anything else. Express counterpart: {@code process.env.NODE_ENV}. Reading the
     * environment is this explicit call the app makes; the builder never reads it by itself.
     */
    // @req SEC-AUTH-002
    public static AuthMode fromEnv() {
        return fromEnvValue(System.getenv(ENV_VARIABLE));
    }

    /** The mode for one value of {@code NARU_ENV}; the testable half of {@link #fromEnv()}. */
    // @req SEC-AUTH-002
    static AuthMode fromEnvValue(@Nullable String value) {
        return value != null && value.toLowerCase(Locale.ROOT).equals("dev") ? DEV : PROD;
    }
}
