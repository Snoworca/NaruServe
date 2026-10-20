package com.snoworca.naruserve;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The nullable-accessor table of docs/api/kotlin.md; the receiver of every row is {@link Request}. */
// @req FR-KT-001
final class KotlinAccessorTable {

    private static final String HEADER = "| Java | Kotlin 타입 |";

    private KotlinAccessorTable() {
    }

    /** Java method name (identifier before the first parenthesis) to nullable (Kotlin type ends in {@code ?}). */
    // @req FR-KT-001
    static Map<String, Boolean> rows() {
        List<String> lines;
        try {
            lines = Arrays.asList(HttpDocsTest.doc("kotlin.md").split("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Boolean> rows = new LinkedHashMap<>();
        int i = 0;
        while (i < lines.size() && !lines.get(i).startsWith(HEADER)) {
            i++;
        }
        if (i == lines.size()) {
            throw new IllegalStateException("kotlin.md has no row starting with " + HEADER);
        }
        // Skip the header and the |---| separator, then read until the table ends.
        for (i += 2; i < lines.size() && lines.get(i).startsWith("|"); i++) {
            String[] cells = lines.get(i).split("\\|");
            String java = cells[1].trim().replace("`", "");
            String name = java.substring(java.indexOf('.') + 1, java.indexOf('('));
            rows.put(name, cells[2].trim().replace("`", "").endsWith("?"));
        }
        return rows;
    }
}
