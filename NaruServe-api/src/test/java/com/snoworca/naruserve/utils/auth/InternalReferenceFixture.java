package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.internal.StandardErrors;
import java.util.function.Supplier;

/**
 * Negative control of AuthArchitectureTest: a utils.auth class that references Core internal and an API above
 * level 21. It lives on the test path only, never in the main jar.
 */
final class InternalReferenceFixture {

    private InternalReferenceFixture() {
    }

    static String reason() {
        Supplier<String> s = () -> StandardErrors.reason(404);
        return s.get();
    }
}
