package com.snoworca.naruserve.utils.auth.internal;

import java.text.Normalizer;
import java.util.Locale;

/** The one login-identifier rule: NFKC, then lower case in {@code Locale.ROOT}, before any store call. */
// @req IR-IAM-003
public final class Usernames {

    private Usernames() {
    }

    /** The normalized form of {@code name}; {@link IllegalArgumentException} when it is blank or longer than 190. */
    // @req IR-IAM-003
    public static String normalize(String name) {
        String n = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
        if (n.isEmpty() || n.length() > 190) {
            throw new IllegalArgumentException("a username needs 1 to 190 characters");
        }
        return n;
    }
}
