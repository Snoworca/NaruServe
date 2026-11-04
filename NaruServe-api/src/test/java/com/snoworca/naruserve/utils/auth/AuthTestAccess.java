package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.utils.auth.spi.KeySource;

/** Opens the package-private test credential seam to tests in other packages; test code only. */
public final class AuthTestAccess {

    /** The header the test credentials read: {@code <userId>:<ver>}. */
    public static final String HEADER = AuthTestSupport.HEADER;

    private AuthTestAccess() {
    }

    /** The MiniJson-backed app codec of the auth tests (NaruServe-api has no Gson). */
    public static com.snoworca.naruserve.spi.JsonCodec json() {
        return new AdminFixture.MiniJsonCodec();
    }

    /** A PROD builder signing with {@code secret}, reading real tokens, with cheap hashing. */
    public static Auth.Builder fastBuilder(String secret) {
        return Auth.builder(secret).hashIterations(1000).logger(new RecordingLogger());
    }

    /** A PROD builder over {@code keys} with the test credentials and cheap hashing. */
    public static Auth.Builder builder(KeySource keys) {
        return Auth.builder(keys).credentials(new AuthTestSupport.TestCredentials()).hashIterations(1000)
                .logger(new RecordingLogger());
    }
}
