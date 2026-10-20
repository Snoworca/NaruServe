package com.snoworca.naruserve.common;

/** Shared numeric bounds for scanner input and the release gate. */
// @req FR-REL-002
// @req FR-REL-003
public final class CvssScores {
    private CvssScores() { }

    public static boolean isValid(double score) {
        return !Double.isNaN(score) && !Double.isInfinite(score) && score >= 0 && score <= 10;
    }
}
