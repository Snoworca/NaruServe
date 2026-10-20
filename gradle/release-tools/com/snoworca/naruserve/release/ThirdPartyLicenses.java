package com.snoworca.naruserve.release;

import com.snoworca.naruserve.release.LicenseCheck.License;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;

/** THIRD-PARTY-LICENSES.md: the writer of releaseLicenses and the staleness check of the default build. */
public final class ThirdPartyLicenses {

    public static final String PROVIDED_HEADING = "## Provided by the container or the build, not shipped";

    private static final String TABLE_HEAD = "| dependency | license | url |\n|---|---|---|\n";

    private ThirdPartyLicenses() {
    }

    /**
     * The file: one {@code | group:name:version | license | url |} row per shipped dependency, then the provided
     * section with one row per {@code provided} entry, each already {@code group:name:version | license | url}.
     */
    // @req FR-REL-004
    public static String render(SortedMap<String, License> shipped, List<String> provided) {
        StringBuilder b = new StringBuilder("# Third-party licenses\n\n"
                + "Written by `./gradlew -Prelease releaseLicenses` from the `runtimeClasspath` of the nine published "
                + "modules; do not edit by hand. The default build fails when the rows differ from the resolved "
                + "dependencies.\n\n");
        b.append(TABLE_HEAD);
        for (Map.Entry<String, License> e : shipped.entrySet()) {
            b.append("| ").append(e.getKey()).append(" | ").append(cell(e.getValue().name)).append(" | ")
                    .append(cell(e.getValue().url)).append(" |\n");
        }
        b.append('\n').append(PROVIDED_HEADING).append("\n\n")
                .append("Compile-only artifacts: the Servlet container or the build supplies them, NaruServe never "
                        + "ships them.\n\n")
                .append(TABLE_HEAD);
        for (String row : provided) {
            b.append("| ").append(row).append(" |\n");
        }
        return b.toString();
    }

    /** Compile-only components of the graph: on a compileClasspath, on no runtimeClasspath, not the project's own. */
    // @req FR-REL-004
    public static SortedSet<String> provided(List<String> graphLines) {
        SortedSet<String> provided = DependencyList.configuration(graphLines, "compileClasspath");
        provided.removeAll(DependencyList.runtime(graphLines));
        return provided;
    }

    /**
     * One message per dependency of the graph without a row, and per row without such a dependency, for the shipped
     * table (runtime dependencies) and the provided table (compile-only dependencies) alike.
     */
    // @req FR-REL-004
    public static List<String> diff(List<String> graphLines, String markdown) {
        SortedSet<String> shippedRows = new TreeSet<>();
        SortedSet<String> providedRows = new TreeSet<>();
        SortedSet<String> rows = shippedRows;
        for (String line : markdown.split("\r?\n")) {
            if (line.startsWith(PROVIDED_HEADING)) {
                rows = providedRows;
            }
            String[] cells = line.trim().split("\\s*\\|\\s*");
            if (line.trim().startsWith("|") && cells.length > 1 && !cells[1].equals("dependency")
                    && !cells[1].startsWith("-")) {
                rows.add(cells[1]);
            }
        }
        List<String> messages = new ArrayList<>();
        compare(DependencyList.runtime(graphLines), shippedRows, "", messages);
        compare(provided(graphLines), providedRows, " (provided section)", messages);
        return messages;
    }

    private static void compare(SortedSet<String> expected, SortedSet<String> rows, String where,
            List<String> messages) {
        for (String dep : expected) {
            if (!rows.contains(dep)) {
                messages.add("missing row for " + dep + " in THIRD-PARTY-LICENSES.md" + where);
            }
        }
        for (String dep : rows) {
            if (!expected.contains(dep)) {
                messages.add("extra row for " + dep + " in THIRD-PARTY-LICENSES.md" + where);
            }
        }
    }

    private static String cell(String value) {
        return value.isEmpty() ? "-" : value.replace("|", "/");
    }
}
