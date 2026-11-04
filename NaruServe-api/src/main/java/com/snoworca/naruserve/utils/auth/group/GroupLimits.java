package com.snoworca.naruserve.utils.auth.group;

/**
 * Bounds of group expansion: the levels walked above the direct groups (default 8) and the groups in the result
 * (default 256). Expansion beyond either fails instead of returning a truncated set, so a request is never authorized
 * on part of a user's groups.
 */
// @req FR-IAM-007
public final class GroupLimits {

    private static final GroupLimits DEFAULTS = new GroupLimits(8, 256);

    private final int maxDepth;
    private final int maxSize;

    private GroupLimits(int maxDepth, int maxSize) {
        this.maxDepth = maxDepth;
        this.maxSize = maxSize;
    }

    /** Depth 8, size 256. */
    // @req FR-IAM-007
    public static GroupLimits defaults() {
        return DEFAULTS;
    }

    /** Other limits; {@code maxDepth} is 0 or more (0: direct groups only) and {@code maxSize} is 1 or more. */
    // @req FR-IAM-007
    public static GroupLimits of(int maxDepth, int maxSize) {
        if (maxDepth < 0 || maxSize < 1) {
            throw new IllegalArgumentException("group limits need maxDepth >= 0 and maxSize >= 1: " + maxDepth + ", "
                    + maxSize);
        }
        return new GroupLimits(maxDepth, maxSize);
    }

    /** Levels walked above the direct groups. */
    public int getMaxDepth() {
        return maxDepth;
    }

    /** Groups allowed in one user's expansion. */
    public int getMaxSize() {
        return maxSize;
    }

    @Override
    public String toString() {
        return "GroupLimits{maxDepth=" + maxDepth + ", maxSize=" + maxSize + "}";
    }
}
