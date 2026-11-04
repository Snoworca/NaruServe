package com.snoworca.naruserve.utils.auth.internal;

/** An invalid argument auth itself refused: answered 400 {@code BAD_REQUEST}, unlike an app's own exception. */
// @req IR-IAM-003
public final class AuthArgumentException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** The refusal with its message. */
    // @req IR-IAM-003
    public AuthArgumentException(String message) {
        this(message, false);
    }

    private final boolean shown;

    /** {@code shown}: the message is a client-facing rule (a password-policy or username refusal, OPS-AUTH-018 AC-1). */
    // @req OPS-AUTH-018
    public AuthArgumentException(String message, boolean shown) {
        super(message);
        this.shown = shown;
    }

    /** True when the auth error layer may put the message in the body. */
    public boolean isShown() {
        return shown;
    }
}
