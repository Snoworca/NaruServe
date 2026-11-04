package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.utils.auth.internal.TestingBridge;
import com.snoworca.naruserve.utils.auth.internal.Usernames;
import com.snoworca.naruserve.utils.auth.jwt.JwtClaims;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.InMemoryUserStore;
import com.snoworca.naruserve.utils.auth.user.UserStore;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The operations of {@code AuthTesting}: real tokens through the TokenService of the Auth, the CSRF token through its
 * CSRF tokens and a key through its API key store, never a copy of the key.
 */
// @req IR-AUTH-005
final class AuthTestingOps implements TestingBridge.Ops {

    // @req IR-AUTH-005
    @Override
    public String tokenFor(Auth auth, String name, String[] roles) {
        UserStore store = auth.userStore();
        if (!(store instanceof InMemoryUserStore)) {
            throw new IllegalStateException("AuthTesting.tokenFor writes the user's roles and works only on an "
                    + "InMemoryUserStore; use tokenForUserId for " + store.getClass().getSimpleName());
        }
        Set<String> wanted = new LinkedHashSet<>(Arrays.asList(roles));
        for (String role : wanted) {
            if (!auth.declaresRole(role)) {
                throw new IllegalArgumentException("the role " + role + " is not declared in the RolePolicy");
            }
        }
        String username = Usernames.normalize(name);
        try {
            AuthUser u = store.findByUsername(username);
            if (u == null) {
                u = store.create(AuthUser.builder().username(username).roles(wanted).enabled(true).build());
            } else {
                String id = u.getId();
                if (id == null || !store.setRoles(id, wanted)) {
                    throw new IllegalStateException("the user " + username + " could not be updated");
                }
                auth.userChanged(id);
                u = store.findById(id);
                if (u == null) {
                    throw new IllegalStateException("the user " + username + " disappeared");
                }
            }
            return auth.tokens().accessOnly(u);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("the user store failed", e);
        }
    }

    // @req IR-AUTH-005
    @Override
    public String tokenForUserId(Auth auth, String userId) {
        AuthUser u;
        try {
            u = auth.userStore().findById(userId);
        } catch (Exception e) {
            throw new IllegalStateException("the user store failed", e);
        }
        if (u == null) {
            throw new IllegalArgumentException("no user with id " + userId);
        }
        return auth.tokens().accessOnly(u);
    }

    // @req IR-AUTH-005
    // @req FR-APIKEY-010
    @Override
    public String apiKeyFor(Auth auth, String ownerUserId, String[] scopes) {
        if (auth.apiKeyStore() == null) {
            throw new AuthStateException("AuthTesting.apiKeyFor needs API keys: configure .apiKeys(store)");
        }
        return auth.apiKeys().issue(ownerUserId, "AuthTesting", null, scopes).getPlainKey();
    }

    // @req IR-AUTH-005
    // @req SEC-FLOW-006
    @Override
    public String csrfTokenFor(Auth auth, String accessToken) {
        JwtClaims c;
        try {
            c = auth.tokens().verifyAccess(accessToken);
        } catch (AuthRejection invalid) {
            throw new IllegalArgumentException("the access token is not valid for this auth");
        }
        String sub = c.getSubject();
        String fam = c.getString("fam");
        if (sub == null || fam == null) {
            throw new IllegalArgumentException("the access token carries no sub or fam");
        }
        return auth.csrfTokens().issue(sub, fam);
    }

    @Override
    public String toString() {
        return "AuthTestingOps";
    }
}
