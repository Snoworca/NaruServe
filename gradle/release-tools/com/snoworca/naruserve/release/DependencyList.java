package com.snoworca.naruserve.release;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/** The shipped dependencies of the published modules, read from build/reports/naruserve/dependency-graphs.txt. */
public final class DependencyList {

    private static final String OWN_GROUP = "com.snoworca.naruserve:";

    private DependencyList() {
    }

    /**
     * The sorted {@code group:name:version} union of the {@code <module>|runtimeClasspath|g:n:v} rows, without the
     * project's own components; compileOnly and test configurations are other rows and so never enter.
     */
    // @req FR-REL-002
    public static SortedSet<String> runtime(List<String> graphLines) {
        SortedSet<String> deps = new TreeSet<>();
        for (String line : graphLines) {
            String[] cells = line.trim().split("\\|");
            if (cells.length == 3 && cells[1].equals("runtimeClasspath") && !cells[2].startsWith(OWN_GROUP)) {
                deps.add(cells[2]);
            }
        }
        return deps;
    }
}
