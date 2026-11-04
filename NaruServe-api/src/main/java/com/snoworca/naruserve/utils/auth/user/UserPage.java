package com.snoworca.naruserve.utils.auth.user;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One page of {@link UserStore#list(int, int, String)}: the users, the total ({@code -1} when unknown) and the range. */
// @req IR-IAM-001
public final class UserPage {

    private final List<AuthUser> items;
    private final long total;
    private final int offset;
    private final int limit;

    /** A page; {@code items} is copied. */
    // @req IR-IAM-001
    public UserPage(List<AuthUser> items, long total, int offset, int limit) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.total = total;
        this.offset = offset;
        this.limit = limit;
    }

    /** The users of this page, unmodifiable. */
    public List<AuthUser> getItems() {
        return items;
    }

    /** Every matching user, or {@code -1} when the store does not count them. */
    public long getTotal() {
        return total;
    }

    /** The index of the first item. */
    public int getOffset() {
        return offset;
    }

    /** The requested page size. */
    public int getLimit() {
        return limit;
    }

    @Override
    public String toString() {
        return "UserPage{items=" + items.size() + ", total=" + total + ", offset=" + offset + ", limit=" + limit + "}";
    }
}
