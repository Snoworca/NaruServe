package com.snoworca.naruserve;

import com.snoworca.naruserve.testing.common.ExampleCodeTokens;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches the ```kotlin blocks of docs/api to the Kotlin tests that hold them. Block n of a file counts its
 * ```kotlin fences from 1; a test marks block n with the line {@code // docs-example: <file>#<n>}, and its extent
 * runs from that line to the next marker line or the end of the source.
 */
// @req FR-KT-004
final class KotlinDocsMarkers {

    private static final Pattern MARKER = Pattern.compile("^\\s*// docs-example: (\\S+#\\d+)\\s*$");
    private static final Pattern ADAPTED = Pattern.compile("^\\s*// adapted: \\S.*$");

    private KotlinDocsMarkers() {
    }

    /** One marker: where it is and the source lines of its extent. */
    private static final class Marker {
        final String path;
        final List<String> extent = new ArrayList<>();

        Marker(String path) {
            this.path = path;
        }
    }

    /**
     * {@code <file>#<n>: ...} for every block without a marker, every marker found more than once, every block line
     * missing from its marker's extent when the extent has no {@code // adapted: <reason>} comment, and every marker
     * that names no block. Executable line tokens retain literals and operators; translated comments do not
     * count as code. English ordinals reuse a Korean runtime marker only after whole-block executable parity.
     */
    // @req FR-KT-004
    static List<String> failures(Map<String, String> docsByFile, Map<String, String> kotlinByPath) {
        Map<String, List<String>> blocks = new LinkedHashMap<>();
        for (Map.Entry<String, String> doc : docsByFile.entrySet()) {
            int n = 0;
            List<String> block = null;
            for (String line : doc.getValue().split("\n", -1)) {
                String trimmed = line.trim();
                if (block == null && trimmed.startsWith("```kotlin")) {
                    block = new ArrayList<>();
                    blocks.put(doc.getKey() + "#" + ++n, block);
                } else if (block != null && trimmed.startsWith("```")) {
                    block = null;
                } else if (block != null && !trimmed.isEmpty()) {
                    block.add(line);
                }
            }
        }

        Map<String, List<Marker>> markers = new LinkedHashMap<>();
        for (Map.Entry<String, String> source : kotlinByPath.entrySet()) {
            Marker current = null;
            for (String line : source.getValue().split("\n", -1)) {
                Matcher m = MARKER.matcher(line);
                if (m.matches()) {
                    current = new Marker(source.getKey());
                    markers.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(current);
                } else if (current != null && !line.trim().isEmpty()) {
                    current.extent.add(line);
                }
            }
        }

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, List<String>> block : blocks.entrySet()) {
            String key = block.getKey();
            String markerKey = key;
            if (key.startsWith("en/")) {
                markerKey = key.substring(3);
                List<String> paired = blocks.get(markerKey);
                if (paired == null) {
                    failures.add(key + ": no paired Korean executable block for runtime coverage");
                    continue;
                }
                if (!ExampleCodeTokens.kotlinTokens(String.join("\n", paired))
                        .equals(ExampleCodeTokens.kotlinTokens(String.join("\n", block.getValue())))) {
                    failures.add(key + ": executable code differs from " + markerKey);
                    continue;
                }
            }
            List<Marker> found = markers.get(markerKey);
            if (found == null) {
                failures.add(key + ": no // docs-example: " + key + " marker in the Kotlin test sources");
            } else if (found.size() > 1) {
                List<String> paths = new ArrayList<>();
                for (Marker m : found) {
                    paths.add(m.path);
                }
                failures.add(key + ": marker found " + found.size() + " times, in " + paths);
            } else {
                String missing = missingLine(block.getValue(), found.get(0));
                if (missing != null) {
                    failures.add(key + ": " + found.get(0).path + " lacks `" + missing
                            + "` and has no // adapted: <reason> comment");
                }
            }
        }
        for (Map.Entry<String, List<Marker>> marker : markers.entrySet()) {
            if (!blocks.containsKey(marker.getKey())) {
                for (Marker m : marker.getValue()) {
                    failures.add(marker.getKey() + ": stale marker in " + m.path + ", no such kotlin block");
                }
            }
        }
        return failures;
    }

    /** The first block line no extent line equals, or null when all are there or the extent is adapted. */
    private static String missingLine(List<String> block, Marker marker) {
        List<String> extent = new ArrayList<>();
        for (String line : marker.extent) {
            if (ADAPTED.matcher(line).matches()) {
                return null;
            }
            extent.add(line);
        }
        for (String wanted : block) {
            List<String> wantedTokens = ExampleCodeTokens.javaTokens(wanted);
            if (wantedTokens.isEmpty()) continue;
            boolean seen = false;
            for (String line : extent) {
                if (ExampleCodeTokens.javaTokens(line).equals(wantedTokens)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                return wanted;
            }
        }
        return null;
    }

}
