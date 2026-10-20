package com.snoworca.naruserve.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Text checks of the build files for the release-only script: the default build never applies, resolves or wires
 * the release plugins, which live in gradle/release.gradle alone.
 */
public class ReleaseScriptTextTest {

    private static final File ROOT = new File(BuildLayoutTest.property("naruserve.rootDir"));

    static final Pattern GUARDED_APPLY = Pattern.compile("if\\s*\\(\\s*project\\.hasProperty\\(\\s*'release'\\s*\\)"
            + "\\s*\\)\\s*\\{\\s*apply\\s*\\(?\\s*from\\s*:\\s*['\"]gradle/release\\.gradle['\"]\\s*\\)?\\s*}");

    /** The lifecycle tasks of the release script (FR-REL-001 rationale) and the plugin tasks behind them. */
    static final String RELEASE_TASK = "\\b(release(Sbom|Scan|Licenses|Check)\\b|cyclonedx\\w*|dependencyCheck\\w*)";

    private static String file(String path) throws IOException {
        return BuildLayoutTest.read(new File(ROOT, path));
    }

    // SDS-AC-3; FR-REL-001 AC-3
    @Test
    public void fr_REL_001_AC3_buildGradleAppliesReleaseScriptOnlyUnderTheReleaseProperty() throws IOException {
        assertTrue("build.gradle holds the guarded apply", GUARDED_APPLY.matcher(file("build.gradle")).find());
    }

    // SDS-AC-3; FR-REL-001 AC-3
    @Test
    public void fr_REL_001_AC3_releasePluginNamesOccurOnlyInReleaseScript() throws IOException {
        for (String path : new String[] {"build.gradle", "settings.gradle", "gradle.properties"}) {
            String text = file(path).toLowerCase(Locale.ROOT);
            for (String name : new String[] {"cyclonedx", "dependencycheck"}) {
                assertFalse(path + " names " + name, text.contains(name));
            }
        }
        assertTrue(file("gradle/release.gradle").toLowerCase(Locale.ROOT).contains("cyclonedx"));
    }

    // SDS-AC-3; FR-REL-001 AC-3
    @Test
    public void fr_REL_001_AC3_releaseScriptPinsEveryPluginVersionAsExactLiteral() throws IOException {
        Matcher m = Pattern.compile("classpath\\s*\\(?\\s*['\"]([^'\"]+)['\"]").matcher(file("gradle/release.gradle"));
        List<String> coordinates = new ArrayList<>();
        while (m.find()) {
            coordinates.add(m.group(1));
        }
        assertFalse("release.gradle declares its plugin classpath", coordinates.isEmpty());
        for (String coordinate : coordinates) {
            String[] gav = coordinate.split(":");
            assertEquals(coordinate, 3, gav.length);
            assertTrue(coordinate + " pins an exact x.y.z version", gav[2].matches("\\d+\\.\\d+\\.\\d+"));
        }
    }

    /** True when a statement wires a release task under check, build or test. */
    static boolean wiresReleaseTaskIntoDefaultBuild(String script) {
        String target = "(tasks\\.named\\(\\s*['\"](check|build|test)['\"]\\s*(,[^)]*)?\\)(\\s*\\.\\s*configure)?"
                + "|\\b(check|build|test))";
        Pattern block = Pattern.compile(target + "\\s*\\{[^}]*\\b(dependsOn|finalizedBy)\\b[^}]*" + RELEASE_TASK);
        Pattern call = Pattern.compile(target + "\\s*\\.\\s*(dependsOn|finalizedBy)\\b[^\\n]*" + RELEASE_TASK);
        return block.matcher(script).find() || call.matcher(script).find();
    }

    // SDS-AC-3; FR-REL-001 AC-3
    @Test
    public void fr_REL_001_AC3_noReleaseTaskIsWiredIntoCheckBuildOrTest() throws IOException {
        assertTrue(wiresReleaseTaskIntoDefaultBuild("tasks.named('check') { dependsOn releaseSbom }"));
        assertTrue(wiresReleaseTaskIntoDefaultBuild("build.finalizedBy releaseScan"));
        assertTrue(wiresReleaseTaskIntoDefaultBuild("p.tasks.named('check').configure { dependsOn 'cyclonedxDirectBom' }"));
        assertTrue(wiresReleaseTaskIntoDefaultBuild("tasks.named('test', Test) { finalizedBy dependencyCheckAggregate }"));
        assertFalse(wiresReleaseTaskIntoDefaultBuild("tasks.named('check') { dependsOn checkNoCoroutines }"));
        for (String path : new String[] {"build.gradle", "gradle/release.gradle"}) {
            assertFalse(path + " wires a release task into check, build or test",
                    wiresReleaseTaskIntoDefaultBuild(file(path)));
        }
    }

    // SDS-AC-1; FR-REL-001 AC-1
    @Test
    public void fr_REL_001_AC1_releaseSbomWritesOneCycloneDxJsonPerModuleFromRuntimeClasspath() throws IOException {
        String script = file("gradle/release.gradle");
        assertTrue(script.contains("tasks.register('releaseSbom'"));
        assertTrue("applied by class", script.contains("org.cyclonedx.gradle.CyclonedxPlugin"));
        assertFalse("never applied by id", Pattern.compile("(id|plugin:|apply)\\s*\\(?\\s*['\"]org\\.cyclonedx")
                .matcher(script).find());
        assertTrue(script.contains("reports/naruserve/sbom"));
        assertTrue(script.contains(".cdx.json"));
        assertTrue(script.contains("runtimeClasspath"));
    }
}
