package com.snoworca.naruserve.docs;

import static com.snoworca.naruserve.docs.WaveFiveDocsTest.contains;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.doc;
import static com.snoworca.naruserve.docs.WaveSevenDocsTest.section;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Wave 8 kotlin.md and json.md statements, found by fixed phrases. SDS-AC-n cites the wave 8 part 3 SDS. */
// @req OPS-KT-001
// @req FR-KT-006
public final class WaveEightKotlinDocsTest {

    static final String NULL_PHRASE =
            "공개 API는 `@NullMarked`(기본 non-null)이고 null일 수 있는 값에만 `@Nullable`이 붙어 있어 Kotlin이 타입을 정확히 압니다.";
    static final String IMPORT_LINE = "import com.snoworca.naruserve.kotlin.body";
    static final String EXTENSION_LINE =
            "inline fun <reified T : Any> Request.body(): T = body(object : TypeRef<T>() {}) ?: throw HttpException(400, \"JSON body is null\")";
    static final String STDLIB_PHRASE = "`kotlin-stdlib`은 이 모듈만 사용하며 이 모듈은 `NaruServe-api`에만 의존합니다.";
    static final String COROUTINE_PHRASE = "Coroutine 지원은 v1 이후 항목입니다. 논블로킹 coroutine 어댑터는 별도 설계가 필요하고 "
            + "v1 핸들러는 동기이기 때문입니다.";
    static final String GSON_KOTLIN_PHRASE = "Gson은 Kotlin의 기본 인자와 null 불가 타입을 이해하지 못합니다.";

    /** The header KotlinAccessorTable (NaruServe-api tests) starts the nullable-accessor table from. */
    private static final String TABLE_HEADER = "| Java | Kotlin 타입 |";

    // SDS-AC-9; IR-KT-002 AC-4
    @Test
    public void FR_DX_006_AC1_JsonDocsKotlinTest_copy_preserves_type_arguments() throws IOException {
        String copy = ReadmeDocsTest.read("NaruServe-api/src/test/kotlin/com/snoworca/naruserve/JsonDocsKotlinTest.kt");
        assertTrue(Arrays.asList(copy.split("\n")).contains(EXTENSION_LINE));
    }

    // SDS-AC-10; OPS-KT-001 AC-1
    @Test
    public void sdsAc10_OPS_KT_001_AC_1_kotlin_md_names_the_module_and_settings_include_it() throws IOException {
        String kotlin = doc("kotlin.md");
        contains("kotlin.md", kotlin, "NaruServe-kotlin", "com.snoworca.naruserve:NaruServe-kotlin:0.1.0-SNAPSHOT");
        assertFalse(kotlin.contains("rest-kotlin"));
        assertTrue(ReadmeDocsTest.read("settings.gradle").contains("include ':NaruServe-kotlin'"));
    }

    // SDS-AC-11; OPS-KT-001 AC-2
    @Test
    public void sdsAc11_OPS_KT_001_AC_2_the_null_paragraph_names_NullMarked_and_no_NonNull() throws IOException {
        String kotlin = doc("kotlin.md");
        assertTrue(section(kotlin, "## null 처리").contains(NULL_PHRASE));
        assertFalse(kotlin.contains("@NonNull"));
    }

    // SDS-AC-12; OPS-KT-001 AC-3
    @Test
    public void sdsAc12_OPS_KT_001_AC_3_the_extension_section_shows_the_import_above_the_pinned_lines()
            throws IOException {
        String ext = section(doc("kotlin.md"), "### NaruServe-kotlin 선택 확장");
        int imp = ext.indexOf("\n" + IMPORT_LINE + "\n");
        int fun = ext.indexOf("\n" + EXTENSION_LINE + "\n");
        int use = ext.indexOf("\nval user = req.body<User>()\n");
        assertTrue(ext, imp >= 0 && fun > imp && use > fun);
        assertTrue(ext, ext.contains(STDLIB_PHRASE));
    }

    // SDS-AC-13; OPS-KT-001 AC-4
    @Test
    public void sdsAc13_OPS_KT_001_AC_4_the_coroutine_section_states_the_post_v1_item() throws IOException {
        String kotlin = doc("kotlin.md");
        contains("kotlin.md Coroutine", section(kotlin, "## Coroutine"), COROUTINE_PHRASE, "`runBlocking`", "`suspend`",
                "`suspend` 핸들러는 v1에서 지원하지 않습니다", "`runBlocking`을 기본 비동기 어댑터로 쓰지 않습니다");
        assertFalse(kotlin.contains("(미확정)"));
    }

    // SDS-AC-14; OPS-KT-001 AC-5
    @Test
    public void sdsAc14_OPS_KT_001_AC_5_the_nullable_accessor_table_keeps_its_twelve_rows() throws IOException {
        List<String> lines = Arrays.asList(doc("kotlin.md").split("\n"));
        int i = 0;
        while (i < lines.size() && !lines.get(i).startsWith(TABLE_HEADER)) {
            i++;
        }
        assertTrue("no " + TABLE_HEADER, i < lines.size());
        List<String> rows = new ArrayList<>();
        // Skip the header and the |---| separator, then read until the table ends, as KotlinAccessorTable does.
        for (i += 2; i < lines.size() && lines.get(i).startsWith("|"); i++) {
            String[] cells = lines.get(i).split("\\|");
            rows.add(cells[1].trim() + " " + cells[2].trim());
        }
        assertEquals(Arrays.asList(
                "`req.param(\"id\")` `String`",
                "`req.query(\"page\")` `String?`",
                "`req.form(name)` `String?`",
                "`req.header(name)` `String?`",
                "`req.cookie(name)` `Cookie?`",
                "`req.file(name)` `UploadFile?`",
                "`req.lastEventId()` `String?`",
                "`req.attributeAs(key, type)` `T?`",
                "`req.queries(name)` `List<String>`",
                "`req.files(name)` `List<UploadFile>`",
                "`req.service(type)` `T`",
                "`req.body(type)` `T?`"), rows);
    }

    // SDS-AC-18; FR-KT-006 AC-4
    @Test
    public void sdsAc18_FR_KT_006_AC_4_json_md_and_kotlin_md_name_the_gson_limit() throws IOException {
        contains("json.md", doc("json.md"), "JsonIOException", "**기본 생성자**를 두세요");
        assertTrue(doc("kotlin.md").contains(GSON_KOTLIN_PHRASE));
    }
}
