package com.snoworca.naruserve.utils.auth.internal;

import com.snoworca.naruserve.utils.auth.Auth;
import org.jspecify.annotations.Nullable;

/**
 * The path from {@code utils.auth.testing.AuthTesting} to the issue paths of an {@link Auth} (its TokenService, CSRF
 * tokens and API key store), which are not public API. Only auth itself installs the operations, when the Auth class
 * initializes; the signing key never crosses this bridge.
 */
// @req IR-AUTH-005
public final class TestingBridge {

    /** The operations AuthTesting delegates to. */
    public interface Ops {
        /** See {@code AuthTesting.tokenFor}. */
        String tokenFor(Auth auth, String name, String[] roles);

        /** See {@code AuthTesting.tokenForUserId}. */
        String tokenForUserId(Auth auth, String userId);

        /** See {@code AuthTesting.apiKeyFor}. */
        String apiKeyFor(Auth auth, String ownerUserId, String[] scopes);

        /** See {@code AuthTesting.csrfTokenFor}. */
        String csrfTokenFor(Auth auth, String accessToken);
    }

    private static final String OWNER = "com.snoworca.naruserve.utils.auth.AuthTestingOps";
    private static volatile @Nullable Ops ops;

    private TestingBridge() {
    }

    /** Installs auth's operations; anything else is refused. */
    public static void install(Ops o) {
        if (!o.getClass().getName().equals(OWNER) || o.getClass().getClassLoader() != Auth.class.getClassLoader()) {
            throw new IllegalArgumentException("only auth installs the testing operations");
        }
        ops = o;
    }

    /** The installed operations (initializing the Auth class when needed). */
    public static Ops ops() {
        Ops o = ops;
        if (o == null) {
            try {
                Class.forName(Auth.class.getName(), true, Auth.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            o = ops;
        }
        if (o == null) {
            throw new IllegalStateException("the auth testing operations are not installed");
        }
        return o;
    }
}
