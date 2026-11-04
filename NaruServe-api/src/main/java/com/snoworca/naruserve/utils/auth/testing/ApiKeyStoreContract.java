package com.snoworca.naruserve.utils.auth.testing;

import com.snoworca.naruserve.utils.auth.apikey.ApiKeyPrefixCollisionException;
import com.snoworca.naruserve.utils.auth.apikey.ApiKeyStore;
import com.snoworca.naruserve.utils.auth.apikey.StoredApiKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * The checks of {@link AuthTesting#verifyApiKeyStore}. Every run uses fresh random owner ids, key ids and prefixes,
 * so it can run again and again on one storage; it only reads and deletes the rows it created, except
 * {@code deleteExpired(now)}, which also removes other keys already expired at the current time. A broken rule is an
 * {@link AssertionError} whose message starts with {@code ApiKeyStore contract:} and names the method.
 */
// @req FR-APIKEY-009
final class ApiKeyStoreContract {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int THREADS = 8;
    private static final long TIMEOUT_SECONDS = 60;

    private ApiKeyStoreContract() {
    }

    /** A thrown store call, rethrown as the failure of the named rule. */
    private interface Call<T> {
        T run() throws Exception;
    }

    private static <T> T call(String rule, Call<T> c) {
        try {
            return c.run();
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("ApiKeyStore contract: " + rule + " threw " + e, e);
        }
    }

    private static AssertionError broken(String rule) {
        return new AssertionError("ApiKeyStore contract: " + rule);
    }

    private static void check(boolean ok, String rule) {
        if (!ok) {
            throw broken(rule);
        }
    }

    // ---------------------------------------------------------------------------------------------- fixtures

    /** Fresh random names for one run. */
    private static final class Run {
        private final String tag;
        private final Set<String> prefixes = new HashSet<>();
        private int ids;

        Run() {
            tag = random(10);
        }

        static String random(int n) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            StringBuilder b = new StringBuilder(n);
            for (int i = 0; i < n; i++) {
                b.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
            }
            return b.toString();
        }

        String owner(String name) {
            return "contract-" + tag + "-" + name;
        }

        String id() {
            return "contract-" + tag + "-k" + (ids++);
        }

        /** A new 8-character prefix, distinct within the run regardless of case. */
        String prefix() {
            while (true) {
                String p = random(8);
                if (prefixes.add(p.toLowerCase(java.util.Locale.ROOT))) {
                    return p;
                }
            }
        }

        static String hash() {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            StringBuilder b = new StringBuilder(64);
            for (int i = 0; i < 64; i++) {
                b.append("0123456789abcdef".charAt(r.nextInt(16)));
            }
            return b.toString();
        }

        StoredApiKey key(String owner, long ver, @Nullable Long expiresAt, long now) {
            return key(prefix(), owner, ver, expiresAt, now);
        }

        StoredApiKey key(String prefix, String owner, long ver, @Nullable Long expiresAt, long now) {
            return StoredApiKey.builder().id(id()).prefix(prefix).secretHash(hash()).ownerUserId(owner)
                    .ownerVer(ver).name("contract key").scopes(Arrays.asList("posts:read", "posts:write"))
                    .expiresAt(expiresAt).createdAt(now - 1000).build();
        }
    }

    private static boolean insert(ApiKeyStore store, StoredApiKey key, int limit, long now) {
        return call("insertIfUnderLimit(" + key.getPrefix() + ", " + limit + ")",
                () -> store.insertIfUnderLimit(key, limit, now));
    }

    private static void inserted(ApiKeyStore store, StoredApiKey key, int limit, long now, String why) {
        check(insert(store, key, limit, now), "insertIfUnderLimit must insert " + why + " and return true");
    }

    private static @Nullable StoredApiKey byId(ApiKeyStore store, String id) {
        return call("findById", () -> store.findById(id));
    }

    private static List<StoredApiKey> owned(ApiKeyStore store, String owner) {
        List<StoredApiKey> list = call("listByOwner", () -> store.listByOwner(owner));
        check(list != null, "listByOwner must not return null");
        return list;
    }

    private static Set<String> ids(List<StoredApiKey> keys) {
        Set<String> s = new HashSet<>();
        for (StoredApiKey k : keys) {
            s.add(k.getId());
        }
        return s;
    }

    private static void sameRow(String method, StoredApiKey expected, @Nullable StoredApiKey actual) {
        check(actual != null, method + " must return the inserted key " + expected.getId());
        String[][] fields = {
            {"id", expected.getId(), actual.getId()},
            {"prefix", expected.getPrefix(), actual.getPrefix()},
            {"secretHash", expected.getSecretHash(), actual.getSecretHash()},
            {"ownerUserId", expected.getOwnerUserId(), actual.getOwnerUserId()},
            {"ownerVer", String.valueOf(expected.getOwnerVer()), String.valueOf(actual.getOwnerVer())},
            {"name", expected.getName(), actual.getName()},
            {"scopes", String.valueOf(new HashSet<>(expected.getScopes())),
                String.valueOf(new HashSet<>(actual.getScopes()))},
            {"expiresAt", String.valueOf(expected.getExpiresAt()), String.valueOf(actual.getExpiresAt())},
            {"createdAt", String.valueOf(expected.getCreatedAt()), String.valueOf(actual.getCreatedAt())},
            {"lastUsedAt", String.valueOf(expected.getLastUsedAt()), String.valueOf(actual.getLastUsedAt())},
            {"issuedByUserId", String.valueOf(expected.getIssuedByUserId()),
                String.valueOf(actual.getIssuedByUserId())},
        };
        for (String[] f : fields) {
            check(Objects.equals(f[1], f[2]), method + " must round-trip " + f[0] + ": stored " + f[1] + ", read "
                    + f[2]);
        }
    }

    private static void cleanup(ApiKeyStore store, String... owners) {
        for (String o : owners) {
            call("deleteAllByOwner", () -> store.deleteAllByOwner(o));
        }
    }

    // ---------------------------------------------------------------------------------------- single store

    // @req FR-APIKEY-009
    static void verify(ApiKeyStore store) {
        Objects.requireNonNull(store, "store");
        long now = System.currentTimeMillis();
        Run run = new Run();
        lookups(store, run, now);
        limits(store, run, now);
        deletes(store, run, now);
        versions(store, run, now);
    }

    /** AC-1: round trip, absent prefix, existing prefix, case-sensitive prefixes. */
    private static void lookups(ApiKeyStore store, Run run, long now) {
        String u = run.owner("lookup");
        StoredApiKey full = run.key(u, 3, now + 3_600_000L, now).toBuilder().lastUsedAt(now - 500)
                .issuedByUserId(run.owner("admin")).build();
        inserted(store, full, 10, now, "a key with every field set");
        sameRow("findByPrefix", full, call("findByPrefix", () -> store.findByPrefix(full.getPrefix())));
        sameRow("findById", full, byId(store, full.getId()));

        StoredApiKey bare = run.key(u, 3, null, now);
        inserted(store, bare, 10, now, "a key with null expiresAt, lastUsedAt and issuedByUserId");
        sameRow("findByPrefix", bare, call("findByPrefix", () -> store.findByPrefix(bare.getPrefix())));
        sameRow("findById", bare, byId(store, bare.getId()));

        String absent = run.prefix();
        check(call("findByPrefix", () -> store.findByPrefix(absent)) == null,
                "findByPrefix of an absent prefix must return null");
        check(byId(store, run.id()) == null, "findById of an absent id must return null");

        StoredApiKey dup = run.key(full.getPrefix(), run.owner("other"), 3, null, now);
        boolean collided = false;
        try {
            store.insertIfUnderLimit(dup, 10, now);
        } catch (ApiKeyPrefixCollisionException e) {
            collided = true;
        } catch (Exception e) {
            throw new AssertionError("ApiKeyStore contract: insertIfUnderLimit of an existing prefix must throw "
                    + "ApiKeyPrefixCollisionException, but threw " + e, e);
        }
        check(collided, "insertIfUnderLimit of an existing prefix must throw ApiKeyPrefixCollisionException");
        check(byId(store, dup.getId()) == null, "insertIfUnderLimit must not store a key whose prefix exists");
        sameRow("findByPrefix after a prefix collision", full,
                call("findByPrefix", () -> store.findByPrefix(full.getPrefix())));

        String tail = Run.random(6);
        StoredApiKey lower = run.key("aB" + tail, u, 3, null, now);
        StoredApiKey upper = run.key("Ab" + tail, u, 3, null, now);
        inserted(store, lower, 10, now, "prefix aB" + tail);
        try {
            inserted(store, upper, 10, now, "prefix Ab" + tail + " beside aB" + tail);
        } catch (AssertionError e) {
            if (e.getCause() instanceof ApiKeyPrefixCollisionException) {
                throw new AssertionError("ApiKeyStore contract: insertIfUnderLimit and findByPrefix must compare "
                        + "prefixes case-sensitively, but Ab" + tail + " collided with aB" + tail, e.getCause());
            }
            throw e;
        }
        StoredApiKey a = call("findByPrefix", () -> store.findByPrefix(lower.getPrefix()));
        StoredApiKey b = call("findByPrefix", () -> store.findByPrefix(upper.getPrefix()));
        check(a != null && a.getId().equals(lower.getId()) && b != null && b.getId().equals(upper.getId()),
                "findByPrefix must compare case-sensitively: aB" + tail + " and Ab" + tail + " are separate rows");

        Set<String> listed = ids(owned(store, u));
        check(listed.equals(new HashSet<>(Arrays.asList(full.getId(), bare.getId(), lower.getId(), upper.getId()))),
                "listByOwner must return exactly the owner's keys: " + listed);
        cleanup(store, u, run.owner("other"));
    }

    /** AC-2: live-key counting excludes expired keys and keys of a lower ownerVer. */
    private static void limits(ApiKeyStore store, Run run, long now) {
        String u = run.owner("limit");
        inserted(store, run.key(u, 5, now - 1, now), 1, now, "an already expired key under the limit");
        inserted(store, run.key(u, 5, now, now), 1, now, "a second key when the only other one expired "
                + "(expiresAt <= now is expired; insertIfUnderLimit must not count expired keys)");
        inserted(store, run.key(u, 4, null, now), 1, now, "an ownerVer 4 key when only expired keys exist");
        inserted(store, run.key(u, 5, now + 60_000L, now), 1, now, "an ownerVer 5 key beside an ownerVer 4 key "
                + "(insertIfUnderLimit must not count keys whose ownerVer is below the new key's)");
        StoredApiKey over = run.key(u, 5, null, now);
        check(!insert(store, over, 1, now), "insertIfUnderLimit must return false when the owner already has "
                + "maxLiveKeys live keys");
        check(byId(store, over.getId()) == null, "insertIfUnderLimit must not store a key over the limit");
        StoredApiKey lower = run.key(u, 4, null, now);
        check(!insert(store, lower, 2, now), "insertIfUnderLimit must count keys of a higher ownerVer as live");
        inserted(store, run.key(u, 5, null, now), 2, now, "a key under a limit of 2 with one live key");
        cleanup(store, u);
    }

    /** AC-2: owner-scoped deletes, delete all, delete expired. */
    private static void deletes(ApiKeyStore store, Run run, long now) {
        String u = run.owner("del");
        String v = run.owner("del-other");
        StoredApiKey k1 = run.key(u, 1, null, now);
        StoredApiKey k2 = run.key(u, 1, null, now);
        StoredApiKey k3 = run.key(u, 1, null, now);
        StoredApiKey kv = run.key(v, 1, null, now);
        for (StoredApiKey k : Arrays.asList(k1, k2, k3, kv)) {
            inserted(store, k, 10, now, "a key for the delete checks");
        }
        check(!call("deleteByIdAndOwner", () -> store.deleteByIdAndOwner(k1.getId(), v)),
                "deleteByIdAndOwner with another owner must return false");
        check(byId(store, k1.getId()) != null, "deleteByIdAndOwner with another owner must not delete the key");
        check(call("deleteByIdAndOwner", () -> store.deleteByIdAndOwner(k1.getId(), u)),
                "deleteByIdAndOwner with the owner must return true");
        check(byId(store, k1.getId()) == null, "deleteByIdAndOwner with the owner must delete the key");
        check(!call("deleteByIdAndOwner", () -> store.deleteByIdAndOwner(k1.getId(), u)),
                "deleteByIdAndOwner of a missing key must return false");
        check(call("deleteById", () -> store.deleteById(k2.getId())), "deleteById must return true for a key");
        check(!call("deleteById", () -> store.deleteById(k2.getId())), "deleteById of a missing key must return false");
        inserted(store, run.key(u, 1, null, now), 10, now, "another key for deleteAllByOwner");
        int all = call("deleteAllByOwner", () -> store.deleteAllByOwner(u));
        check(all == 2, "deleteAllByOwner must delete all 2 of the owner's keys and return 2, returned " + all);
        check(owned(store, u).isEmpty(), "deleteAllByOwner must remove every key of the owner");
        check(byId(store, k3.getId()) == null, "deleteAllByOwner must remove every key of the owner");
        int again = call("deleteAllByOwner", () -> store.deleteAllByOwner(u));
        check(again == 0, "deleteAllByOwner must be idempotent and return 0 the second time, returned " + again);
        check(byId(store, kv.getId()) != null, "deleteAllByOwner must not remove another owner's keys");

        String e = run.owner("exp");
        StoredApiKey past = run.key(e, 1, now - 10, now);
        StoredApiKey edge = run.key(e, 1, now, now);
        StoredApiKey later = run.key(e, 1, now + 3_600_000L, now);
        StoredApiKey never = run.key(e, 1, null, now);
        for (StoredApiKey k : Arrays.asList(past, edge, later, never)) {
            inserted(store, k, 10, now, "a key for the deleteExpired check");
        }
        int expired = call("deleteExpired", () -> store.deleteExpired(now));
        check(byId(store, past.getId()) == null, "deleteExpired(now) must delete a key with expiresAt < now");
        check(byId(store, edge.getId()) == null, "deleteExpired(now) must delete a key with expiresAt == now");
        check(byId(store, later.getId()) != null, "deleteExpired(now) must keep a key with expiresAt > now");
        check(byId(store, never.getId()) != null, "deleteExpired(now) must keep a key with a null expiresAt");
        check(expired >= 2, "deleteExpired must return how many keys it deleted (at least 2), returned " + expired);
        cleanup(store, v, e);
    }

    /** AC-3: stale and future deletes over ownerVer 4, 5 and 6. */
    private static void versions(ApiKeyStore store, Run run, long now) {
        String s = run.owner("stale");
        List<StoredApiKey> stale = new ArrayList<>();
        String f = run.owner("future");
        List<StoredApiKey> future = new ArrayList<>();
        for (long v = 4; v <= 6; v++) {
            StoredApiKey a = run.key(s, v, null, now);
            StoredApiKey b = run.key(f, v, null, now);
            inserted(store, a, 10, now, "an ownerVer " + v + " key");
            inserted(store, b, 10, now, "an ownerVer " + v + " key");
            stale.add(a);
            future.add(b);
        }
        int n = call("deleteStaleByOwner", () -> store.deleteStaleByOwner(s, 5));
        check(n == 1, "deleteStaleByOwner(owner, 5) over ownerVer 4, 5, 6 must delete only the ownerVer 4 key and "
                + "return 1, returned " + n);
        check(ids(owned(store, s)).equals(new HashSet<>(Arrays.asList(stale.get(1).getId(), stale.get(2).getId()))),
                "deleteStaleByOwner(owner, 5) must delete only keys with ownerVer < 5");
        int m = call("deleteFutureByOwner", () -> store.deleteFutureByOwner(f, 5));
        check(m == 1, "deleteFutureByOwner(owner, 5) over ownerVer 4, 5, 6 must delete only the ownerVer 6 key and "
                + "return 1, returned " + m);
        check(ids(owned(store, f)).equals(new HashSet<>(Arrays.asList(future.get(0).getId(), future.get(1).getId()))),
                "deleteFutureByOwner(owner, 5) must delete only keys with ownerVer > 5");
        cleanup(store, s, f);
    }

    // ------------------------------------------------------------------------------------- two instances

    // @req FR-APIKEY-009
    static void verifyConcurrent(ApiKeyStore a, ApiKeyStore b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        long now = System.currentTimeMillis();
        Run run = new Run();
        String u = run.owner("race");
        int limit = 3;
        for (int i = 0; i < limit - 1; i++) {
            inserted(a, run.key(u, 7, null, now), limit, now, "a key below the limit before the race");
        }
        final List<StoredApiKey> keys = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            keys.add(run.key(u, 7, null, now));
        }
        final CountDownLatch ready = new CountDownLatch(THREADS);
        final CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                final ApiKeyStore s = i % 2 == 0 ? a : b;
                final StoredApiKey k = keys.get(i);
                results.add(pool.submit(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        ready.countDown();
                        go.await();
                        return s.insertIfUnderLimit(k, limit, now);
                    }
                }));
            }
            await(ready);
            go.countDown();
            int wins = 0;
            for (int i = 0; i < THREADS; i++) {
                try {
                    if (results.get(i).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        wins++;
                    }
                } catch (ExecutionException e) {
                    throw new AssertionError("ApiKeyStore contract: concurrent insertIfUnderLimit over two instances "
                            + "must finish without an exception (retry inside the store), but thread " + i + " got "
                            + e.getCause(), e.getCause());
                } catch (TimeoutException e) {
                    throw broken("concurrent insertIfUnderLimit over two instances did not finish within "
                            + TIMEOUT_SECONDS + " s");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("ApiKeyStore contract: interrupted", e);
                }
            }
            check(wins == 1, "concurrent insertIfUnderLimit over two instances at limit-1 must return true for "
                    + "exactly one of " + THREADS + " threads, " + wins + " returned true");
        } finally {
            pool.shutdownNow();
        }
        int live = 0;
        for (StoredApiKey k : owned(a, u)) {
            Long exp = k.getExpiresAt();
            if ((exp == null || exp > now) && k.getOwnerVer() >= 7) {
                live++;
            }
        }
        check(live == limit, "after concurrent insertIfUnderLimit the live key count must equal the limit " + limit
                + ", found " + live);
        cleanup(a, u);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw broken("concurrent insertIfUnderLimit threads did not start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("ApiKeyStore contract: interrupted", e);
        }
    }
}
