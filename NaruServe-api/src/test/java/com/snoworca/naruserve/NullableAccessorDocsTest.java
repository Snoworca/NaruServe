package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.Test;

/** Every row of the nullable-accessor table of docs/api/kotlin.md has a Kotlin test function (FR-KT-005 AC-4). */
public class NullableAccessorDocsTest {

    private static String kotlinSource() throws IOException {
        File f = new File(System.getProperty("user.dir"),
                "src/test/kotlin/com/snoworca/naruserve/NullableAccessorKotlinTest.kt");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** {@code kotlin.md row <key> has no test function} per key without a {@code fun `...accessor_<key>} name. */
    private static List<String> missing(Collection<String> keys, String source) {
        List<String> failures = new ArrayList<>();
        for (String key : keys) {
            // The name ends at a character that cannot continue the key, so accessor_files does not count for file.
            Pattern fun = Pattern.compile("(?m)^\\s*fun `[^`\\n]*accessor_" + Pattern.quote(key) + "(?![A-Za-z0-9_])[^`\\n]*`");
            if (!fun.matcher(source).find()) {
                failures.add("kotlin.md row " + key + " has no test function");
            }
        }
        return failures;
    }

    // SDS-AC-8; FR-KT-005 AC-4
    @Test
    public void everyTableRowHasAFunction() throws IOException {
        Collection<String> keys = KotlinAccessorTable.rows().keySet();
        assertEquals(Arrays.asList("param", "query", "form", "header", "cookie", "file", "lastEventId", "attributeAs",
                "queries", "files", "service", "body"), new ArrayList<>(keys));
        assertEquals(Collections.emptyList(), missing(keys, kotlinSource()));
    }

    // SDS-AC-8; FR-KT-005 AC-4
    @Test
    public void sdsAc8_FR_KT_005_AC4_syntheticMissingRowIsNamed() throws IOException {
        List<String> keys = new ArrayList<>(KotlinAccessorTable.rows().keySet());
        keys.add("cookies");
        assertEquals(Collections.singletonList("kotlin.md row cookies has no test function"),
                missing(keys, kotlinSource()));
        assertEquals(Collections.singletonList("kotlin.md row file has no test function"),
                missing(Collections.singletonList("file"), "fun `accessor_files is a list`() {}"));
    }
}
