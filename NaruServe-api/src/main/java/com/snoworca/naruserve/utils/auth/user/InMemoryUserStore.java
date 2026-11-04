package com.snoworca.naruserve.utils.auth.user;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Users in memory, lost on restart: the default store when {@code .users(...)} is not called, and the one for tests.
 * Thread-safe. Ids are decimal strings counting up from 1 and are never reused. Not shared between nodes, so
 * {@code Deployment.MULTI_INSTANCE} refuses it.
 */
// @req IR-IAM-001
public final class InMemoryUserStore implements UserStore {

    private final Map<String, AuthUser> byId = new LinkedHashMap<>();
    private final Map<String, String> idByName = new LinkedHashMap<>();
    private long nextId = 1;

    /** An empty store. */
    // @req IR-IAM-001
    public InMemoryUserStore() {
    }

    @Override
    public synchronized @Nullable AuthUser findByUsername(String username) {
        String id = idByName.get(username);
        return id == null ? null : byId.get(id);
    }

    @Override
    public synchronized @Nullable AuthUser findById(String id) {
        return byId.get(id);
    }

    // @req IR-IAM-001
    @Override
    public synchronized AuthUser create(AuthUser user) {
        if (user.getId() != null) {
            throw new IllegalArgumentException("a new user has no id yet: " + user.getId());
        }
        if (idByName.containsKey(user.getUsername())) {
            throw new DuplicateUserException("user " + user.getUsername() + " already exists");
        }
        String id = String.valueOf(nextId++);
        AuthUser stored = user.toBuilder().id(id).build();
        byId.put(id, stored);
        idByName.put(stored.getUsername(), id);
        return stored;
    }

    // @req IR-IAM-001
    @Override
    public synchronized @Nullable AuthUser update(AuthUser user) {
        String id = user.getId();
        AuthUser old = id == null ? null : byId.get(id);
        if (old == null) {
            return null;
        }
        String owner = idByName.get(user.getUsername());
        if (owner != null && !owner.equals(id)) {
            throw new DuplicateUserException("user " + user.getUsername() + " already exists");
        }
        idByName.remove(old.getUsername());
        idByName.put(user.getUsername(), id);
        byId.put(id, user);
        return user;
    }

    @Override
    public synchronized boolean delete(String id) {
        AuthUser old = byId.remove(id);
        if (old == null) {
            return false;
        }
        idByName.remove(old.getUsername());
        return true;
    }

    /** Users in creation order whose username contains {@code search} (ignoring case); null matches all. */
    // @req IR-IAM-001
    @Override
    public synchronized UserPage list(int offset, int limit, @Nullable String search) {
        if (offset < 0 || limit < 0) {
            throw new IllegalArgumentException("offset and limit must not be negative: " + offset + ", " + limit);
        }
        String needle = search == null ? null : search.toLowerCase(Locale.ROOT);
        List<AuthUser> matching = new ArrayList<>();
        for (AuthUser u : byId.values()) {
            if (needle == null || u.getUsername().toLowerCase(Locale.ROOT).contains(needle)) {
                matching.add(u);
            }
        }
        int from = Math.min(offset, matching.size());
        int to = (int) Math.min((long) from + limit, matching.size());
        return new UserPage(matching.subList(from, to), matching.size(), offset, limit);
    }

    @Override
    public synchronized String toString() {
        return "InMemoryUserStore{users=" + byId.size() + "}";
    }
}
