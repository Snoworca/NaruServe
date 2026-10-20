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
        return configuration(graphLines, "runtimeClasspath");
    }

    /** The sorted {@code group:name:version} of the {@code <module>|<name>|g:n:v} rows, without the project's own. */
    // @req FR-REL-002
    // @req FR-REL-004
    public static SortedSet<String> configuration(List<String> graphLines, String name) {
        SortedSet<String> deps = new TreeSet<>();
        for (String line : graphLines) {
            String[] cells = line.trim().split("\\|");
            if (cells.length == 3 && cells[1].equals(name) && !cells[2].startsWith(OWN_GROUP)) {
                deps.add(cells[2]);
            }
        }
        return deps;
    }

    /** {@code group:name} of a {@code group:name:version} coordinate; a finding of another version still blocks. */
    // @req FR-REL-003
    // @req FR-REL-004
    public static String module(String coordinate) {
        return coordinate.substring(0, coordinate.lastIndexOf(':'));
    }
}
