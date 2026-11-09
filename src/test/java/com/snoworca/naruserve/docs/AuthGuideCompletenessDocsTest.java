package com.snoworca.naruserve.docs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.gson.GsonJsonCodec;
import com.snoworca.naruserve.internal.StandardErrors;
import com.snoworca.naruserve.testing.common.ExampleCodeTokens;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.AuthErrorCode;
import com.snoworca.naruserve.utils.auth.AuthProfile;
import com.snoworca.naruserve.utils.auth.AuthRejection;
import com.snoworca.naruserve.utils.auth.TokenService;
import com.snoworca.naruserve.utils.auth.apikey.InMemoryApiKeyStore;
import com.snoworca.naruserve.utils.auth.group.InMemoryGroupStore;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import com.snoworca.naruserve.utils.auth.testing.AuthTesting;
import com.snoworca.naruserve.utils.auth.user.AuthUser;
import com.snoworca.naruserve.utils.auth.user.DuplicateUserException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * The auth guide lists every builder setting and error code, routes the browser and token-lifetime tasks and gives
 * the HTTP test recipe and the groups section an Express counterpart (OPS-DX-006).
 */
// @req OPS-DX-006
public class AuthGuideCompletenessDocsTest {

    private static final String KO = "docs/api/auth.md";
    private static final String EN = "docs/api/en/auth.md";

    private static boolean en(String f) {
        return f.contains("/en/");
    }

    private static void has(String where, String text, String needle) {
        assertTrue(where + " lacks: " + needle, text.contains(needle));
    }

    /** The builder table rows: the first table of the "Auth builder" section. */
    private static List<String> builderRows(String f) throws IOException {
        String section = ReleaseNotesDocsTest.section(ReadmeDocsTest.read(f),
                en(f) ? "## The Auth builder" : "## Auth 빌더");
        return AuthGuideDocsTest.tableRows(section);
    }

    /** The one row whose first cell names {@code .name(}; fails when none does. */
    private static String rowOf(String f, List<String> rows, String name) {
        for (String row : rows) {
            if (row.split("\\|")[1].contains("`." + name + "(")) {
                return row;
            }
        }
        throw new AssertionError(f + ": the builder table has no row for ." + name + "(");
    }

    /** The public method names Auth.Builder declares, build() and the Object methods excluded, overloads once. */
    static Set<String> builderMethodNames() {
        Set<String> names = new TreeSet<>();
        for (Method m : Auth.Builder.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && !m.isSynthetic() && !m.isBridge()
                    && !Modifier.isStatic(m.getModifiers())) {
                names.add(m.getName());
            }
        }
        names.removeAll(Arrays.asList("build", "toString", "equals", "hashCode"));
        return names;
    }

    /** The first (method) cell of each builder-table row, one per line; description and Express cells are out. */
    static String methodCells(List<String> rows) {
        List<String> cells = new ArrayList<>();
        for (String row : rows) {
            cells.add(row.split("\\|")[1]);
        }
        return String.join("\n", cells);
    }

    /** The names of {@code methods} that no {@code .name(} in {@code table} (the method cells) mentions. */
    static List<String> missingFromTable(Set<String> methods, String table) {
        List<String> missing = new ArrayList<>();
        for (String name : methods) {
            if (!table.contains("." + name + "(")) {
                missing.add(name);
            }
        }
        return missing;
    }

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    /** {@code ms} in {@code unit} as the builder table writes it ("15 minutes", "15분"); fails when it does not divide. */
    static String human(long ms, long unit, boolean english) {
        assertEquals(ms + " ms is a whole number of the unit " + unit, 0, ms % unit);
        long n = ms / unit;
        if (!english) {
            return n + (unit == DAY ? "일" : unit == HOUR ? "시간" : "분");
        }
        String name = unit == DAY ? "day" : unit == HOUR ? "hour" : "minute";
        return n + " " + name + (n == 1 ? "" : "s");
    }

    /** The value of a private field of {@code target}. */
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    /** The value of a static field of {@link Auth}. */
    private static Object staticField(String name) throws ReflectiveOperationException {
        Field f = Auth.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    // OPS-DX-006 AC-1: one row per previously missing builder method, with its default and a link to its section;
    // every public Auth.Builder method appears in the table (reflection)
    @Test
    public void OPS_DX_006_AC1_builderTableListsEveryBuilderMethod() throws Exception {
        // the defaults as the code sets them: a fresh builder's fields and the lifetimes of its profile
        Auth.Builder fresh = Auth.builder("0123456789abcdef0123456789abcdef-docs");
        AuthProfile profile = (AuthProfile) field(fresh, "profile");
        Method lifetimes = Auth.class.getDeclaredMethod("lifetimes", AuthProfile.class);
        lifetimes.setAccessible(true);
        long[] ttl = (long[]) lifetimes.invoke(null, profile);
        long access = ttl[0];
        long refresh = ttl[1];
        long insecureAccess = (Long) staticField("INSECURE_ACCESS_TTL_MILLIS");
        long insecureRefresh = (Long) staticField("INSECURE_REFRESH_TTL_MILLIS");
        long userCacheTtl = (Long) field(fresh, "userCacheTtl");
        long reuseGrace = (Long) field(fresh, "reuseGraceMillis");
        int userIpMax = (Integer) field(fresh, "loginUserIpMax");
        int ipMax = (Integer) field(fresh, "loginIpMax");
        int userMax = (Integer) field(fresh, "loginUserMax");
        long window = (Long) field(fresh, "loginWindowMillis");
        for (String unset : new String[] {"loginThrottle", "deviceTrust", "lockout", "tokenCodec"}) {
            assertEquals("Auth.Builder." + unset + " is unset by default", null, field(fresh, unset));
        }
        for (String f : new String[] {KO, EN}) {
            String doc = ReadmeDocsTest.read(f);
            Set<String> anchors = AuthGuideDocsTest.anchors(doc);
            List<String> rows = builderRows(f);
            List<String> missing = missingFromTable(builderMethodNames(), methodCells(rows));
            assertEquals(f + ": Auth.Builder methods missing from the builder table", Collections.emptyList(), missing);
            // {method, default needle, section heading}; the default values come from the code
            String[][] expected = en(f) ? new String[][] {
                {"accessTtl", "access " + human(access, MINUTE, true), "Tokens: TokenService and TokenPair"},
                {"refreshTtl", "refresh " + human(refresh, DAY, true), "Tokens: TokenService and TokenPair"},
                {"userCacheTtl", "default " + userCacheTtl + " ms", "Principal cache: userCacheTtl"},
                {"loginThrottle", "follows the user store", "Login throttling store: LoginThrottle"},
                {"loginFailureLimits", "default " + userIpMax + ", " + ipMax + " and " + userMax + " per "
                        + human(window, MINUTE, true) + " (" + window + " ms)", "Login throttling store: LoginThrottle"},
                {"deviceTrust", "follows the user store", "Trusted devices: DeviceTrust"},
                {"lockout", "off when not called", "Account lockout: Lockout"},
                {"tokenCodec", "HS256", "Refresh reuse detection and TokenCodec"},
                {"reuseGrace", "default " + reuseGrace + " ", "Refresh reuse detection and TokenCodec"},
            } : new String[][] {
                {"accessTtl", "access " + human(access, MINUTE, false), "토큰: TokenService와 TokenPair"},
                {"refreshTtl", "refresh " + human(refresh, DAY, false), "토큰: TokenService와 TokenPair"},
                {"userCacheTtl", "기본 " + userCacheTtl + " ms", "주체 캐시: userCacheTtl"},
                {"loginThrottle", "사용자 저장소를 따릅니다", "로그인 제한 저장소: LoginThrottle"},
                {"loginFailureLimits", "기본 " + human(window, MINUTE, false) + "에 " + userIpMax + ", " + ipMax + ", "
                        + userMax + "회(" + window + " ms)", "로그인 제한 저장소: LoginThrottle"},
                {"deviceTrust", "사용자 저장소를 따릅니다", "신뢰 기기: DeviceTrust"},
                {"lockout", "부르지 않으면 꺼짐", "계정 잠금: Lockout"},
                {"tokenCodec", "HS256", "refresh 재사용 감지와 TokenCodec"},
                {"reuseGrace", "기본 " + reuseGrace + "(", "refresh 재사용 감지와 TokenCodec"},
            };
            for (String[] e : expected) {
                String row = rowOf(f, rows, e[0]);
                has(f + " ." + e[0] + " row", row, e[1]);
                String anchor = AuthGuideDocsTest.anchor(e[2]);
                assertTrue(f + ": no heading for #" + anchor, anchors.contains(anchor));
                has(f + " ." + e[0] + " row", row, "](#" + anchor + ")");
            }
            has(f + " .loginThrottle row", rowOf(f, rows, "loginThrottle"), "`<name>-login-throttle.json`");
            has(f + " .deviceTrust row", rowOf(f, rows, "deviceTrust"), "`<name>-device-trust.json`");
            String ttlRow = rowOf(f, rows, "accessTtl");
            has(f + " .accessTtl row", ttlRow, "`" + profile.name() + "`");
            has(f + " .accessTtl row", ttlRow, en(f)
                    ? human(insecureAccess, MINUTE, true) + " and " + human(insecureRefresh, HOUR, true)
                    : human(insecureAccess, MINUTE, false) + "과 " + human(insecureRefresh, HOUR, false));
            has(f + " .accessTtl row", rowOf(f, rows, "accessTtl"), "`.allowInsecureTransport`");
            // express-jwt only verifies; signing is jsonwebtoken's sign
            has(f + " .tokenCodec row", rowOf(f, rows, "tokenCodec").split("\\|")[3], "`jsonwebtoken`");
            for (String line : doc.split("\n")) {
                assertTrue(f + ": credits express-jwt with signing: " + line,
                        !(line.contains("`express-jwt`") && line.contains("`sign`")) || line.contains("`jsonwebtoken`"));
            }
        }
        // the reflection sees the builder, and the comparison names what a table lacks (negative control)
        Set<String> names = builderMethodNames();
        assertTrue(names.containsAll(Arrays.asList("mode", "accessTtl", "reuseGrace", "allowPreAuthMiddleware")));
        assertTrue("package-private test hooks are not listed: " + names, !names.contains("hashIterations"));
        assertEquals(Collections.singletonList("mode"),
                missingFromTable(new TreeSet<>(Arrays.asList("mode", "profile")), "| `.profile(AuthProfile)` |"));
    }

    // OPS-DX-006 AC-1: only the method cell counts, so a name that appears only in a description cell is missing
    // (negative control: removing a method's own row from the real table is detected)
    @Test
    public void OPS_DX_006_AC1_aNameOnlyInADescriptionCellCountsAsMissing() throws IOException {
        List<String> synthetic = Arrays.asList(
                "| `.recoveryFromEnv()` | `.recovery(true)` when the variable is set | — |",
                "| `.maxKeysPerUser(int)` | live keys; `auth.apiKeys().issue` is not limited | — |");
        assertEquals(Arrays.asList("apiKeys", "recovery"), missingFromTable(
                new TreeSet<>(Arrays.asList("apiKeys", "maxKeysPerUser", "recovery", "recoveryFromEnv")),
                methodCells(synthetic)));
        for (String f : new String[] {KO, EN}) {
            List<String> rows = builderRows(f);
            assertEquals(f + ": the real table is complete", Collections.emptyList(),
                    missingFromTable(builderMethodNames(), methodCells(rows)));
            assertEquals(f + ": the .recovery( row removed", Arrays.asList("recovery", "user"),
                    missingFromTable(builderMethodNames(), methodCells(without(rows, "`.recovery("))));
            assertEquals(f + ": the .apiKeys( row removed", Collections.singletonList("apiKeys"),
                    missingFromTable(builderMethodNames(), methodCells(without(rows, "`.apiKeys("))));
        }
    }

    /** {@code rows} less the one row whose method cell holds {@code needle}. */
    private static List<String> without(List<String> rows, String needle) {
        List<String> kept = new ArrayList<>();
        for (String row : rows) {
            if (!row.split("\\|")[1].contains(needle)) {
                kept.add(row);
            }
        }
        assertEquals("exactly one row holds " + needle + " in its method cell", rows.size() - 1, kept.size());
        return kept;
    }

    // OPS-DX-006 AC-2: the first table holds exactly the AuthErrorCode codes; the codes auth sends outside
    // AuthErrorCode follow in a code | status | when table, spelled as the code sends them, each with when auth sends
    // it; then the PROD last-layer rule
    @Test
    public void OPS_DX_006_AC2_errorCodeParagraphListsTheCodesOutsideTheEnum() throws Exception {
        Method coreCode = Auth.class.getDeclaredMethod("coreCode", int.class);
        coreCode.setAccessible(true);
        // {status, needles of its "when" cell joined by ";;", KO then EN}
        String[][] when = {
            {"400", "JSON 객체가 아닌 auth 본문;;앱 라우트에서 `auth.users()`·`auth.apiKeys()`·내장 그룹 저장소(`InMemoryGroupStore`, `FileGroupStore`) 호출이 던진 auth "
                    + "`IllegalArgumentException`;;`auth.errors()`·`auth.errorMiddleware()`·PROD의 마지막 오류 층이 답함",
                "not a JSON object;;an auth `IllegalArgumentException` thrown by `auth.users()`, `auth.apiKeys()` or a "
                    + "built-in group store call (`InMemoryGroupStore`, `FileGroupStore`) in an app route;;answered by `auth.errors()`, `auth.errorMiddleware()` or the "
                    + "PROD final error layer"},
            {"404", "어떤 라우트도 받지 않는 경로;;`.register(true)` 없이 부른 `POST /auth/register`;;API 키나 그룹 관리를 "
                    + "설정하지 않음;;setup이 열리지 않음;;관리·키 엔드포인트에 준 없는 사용자·그룹·API 키 id;;앱 라우트에서는 "
                    + "`auth.users()`와 `auth.apiKeys().revoke`가 null이나 false를 돌려주고;;없는 키 소유자;;"
                    + "내장 그룹 저장소(`InMemoryGroupStore`, `FileGroupStore`)의 없는 그룹에 멤버·부모·역할을 더하는 호출은 위 400",
                "no route serves;;`POST /auth/register` without `.register(true)`;;API keys or group admin not "
                    + "configured;;setup not open;;a user, group or API key id that does not exist, on the admin and "
                    + "key endpoints;;in an app route `auth.users()` and `auth.apiKeys().revoke` return null or false;;"
                    + "an unknown key owner;;adding a member, parent or role to a group that does not exist in a "
                    + "built-in group store (`InMemoryGroupStore`, `FileGroupStore`), throws the 400 above"},
            // 405 and the unserved-path 404 come from the last layer under the auth mounts, after default deny, so a request
            // without a token gets 401 first (Auth.isAnonymousEntry takes POST only)
            {"405", "`Allow` 헤더;;토큰 없이 부르면;;위의 받지 않는 경로에서도 기본 거부가 먼저 401로 답합니다",
                "`Allow` header;;Without a token, default deny answers 401 first;;for the unserved path above"},
            {"409", "`POST /auth/admin/groups`", "`POST /auth/admin/groups`"},
            {"413", "JSON 본문 한도", "JSON body limit"},
            // Auth.jsonBodyOk judges POST only and skips an API-key request to an admin route (keyOnAdmin); PATCH /auth/me
            // and that key request reach AccountEndpoints.parse -> the router error layer
            {"415", "JSON이 아닌 auth 본문;;auth가 직접 답하므로(관리 라우트로 가는 API 키 요청을 뺀 POST 본문은 전역 auth "
                    + "미들웨어, 그 밖에는 auth 라우터의 오류 층)",
                "non-JSON auth body;;Auth answers it itself (the global auth middleware for a POST body other than an "
                    + "API-key request to an admin route, the auth router's error layer otherwise)"},
            // 422 only on the auth HTTP endpoints (AccountEndpoints.checkNewPassword, AdminEndpoints.newPassword);
            // auth.users().create throws AuthArgumentException, which Auth.authError answers 400 BAD_REQUEST
            {"422", "auth 엔드포인트(;;`POST /auth/register`;;`POST /auth/setup`;;`POST /auth/password`;;"
                    + "`POST /auth/admin/users`;;`PATCH /auth/admin/users/:id`;;새 비밀번호;;앱 라우트에서 "
                    + "`auth.users().create`;;400 `BAD_REQUEST`",
                "new password;;on an auth endpoint (;;`POST /auth/register`;;`POST /auth/setup`;;"
                    + "`POST /auth/password`;;`POST /auth/admin/users`;;`PATCH /auth/admin/users/:id`;;"
                    + "`auth.users().create` in an app route;;400 `BAD_REQUEST`"},
            {"500", "auth 라우터의 그 밖의 예외", "other exception in the auth routers"},
            {"501", "`GET /auth/admin/users`;;DEV와 PROD 모두", "`GET /auth/admin/users`;;in DEV and PROD"},
            {"503", "store·throttle·리스너·유출 검사·해시 슬롯·그룹 확장 한도 실패;;`Retry-After: 1`;;"
                    + "`AuthUnavailableException`의 기본 코드",
                "A store, throttle, listener, breach-check, hash-slot or group-expansion-limit failure;;"
                    + "`Retry-After: 1`;;the default code of `AuthUnavailableException`"},
        };
        assertEquals("HTTP_409", coreCode.invoke(null, 409)); // auth's 409 CONFLICT is AdminEndpoints', not coreCode's
        assertEquals("HTTP_409", StandardErrors.code(409)); // nor Core's
        // the first table: {code -> status} of every AuthErrorCode constant
        List<String> enumRows = new ArrayList<>();
        for (AuthErrorCode c : AuthErrorCode.values()) {
            enumRows.add("`" + c.getCode() + "` " + c.getStatus());
        }
        Collections.sort(enumRows);
        for (String f : new String[] {KO, EN}) {
            String doc = ReadmeDocsTest.read(f);
            String section = ReleaseNotesDocsTest.section(doc,
                    en(f) ? "## Errors: AuthRejection, AuthUnavailableException, AuthErrorCode"
                            : "## 오류: AuthRejection, AuthUnavailableException, AuthErrorCode");
            List<String> firstRows = new ArrayList<>();
            for (String row : AuthGuideDocsTest.tableRows(section)) {
                String[] cells = row.split("\\|");
                firstRows.add(cells[1].trim() + " " + cells[2].trim());
            }
            Collections.sort(firstRows);
            assertEquals(f + ": the first table holds exactly the AuthErrorCode codes", enumRows, firstRows);
            String lead = en(f) ? "In the table below, every code except `CONFLICT`"
                    : "아래 표에서 `CONFLICT`를 뺀 코드는";
            int at = section.indexOf("\n" + lead);
            assertTrue(f + ": no lead line for the codes outside AuthErrorCode", at >= 0);
            // the bullet says once that the second table holds the codes outside AuthErrorCode; the lead line does
            // not repeat it
            for (String repeat : en(f) ? new String[] {"which are not in `AuthErrorCode`", "auth also sends the codes"}
                    : new String[] {"`AuthErrorCode`에 없는 아래 코드", "아래 코드도 보냅니다"}) {
                assertTrue(f + ": the lead line repeats the bullet: " + repeat, !section.contains(repeat));
            }
            String after = section.substring(at + 1);
            // the lead line names CONFLICT as the one code Core does not send, and Core's HTTP_409 for it
            String leadLine = after.split("\n")[0];
            for (String needle : en(f)
                    ? new String[] {"every code except `CONFLICT` is the one Core sends for that status",
                        "`CONFLICT` is auth's own", "(Core sends `" + StandardErrors.code(409) + "`)"}
                    : new String[] {"`CONFLICT`를 뺀 코드는 Core가 그 상태에 보내는 코드와 같습니다",
                        "`CONFLICT`는 auth만 쓰는 코드", "Core는 409를 `" + StandardErrors.code(409) + "`로 보냅니다"}) {
                has(f + " lead line", leadLine, needle);
            }
            for (String old : en(f) ? new String[] {"They use Core's spelling.", "in Core's spelling"}
                    : new String[] {"철자는 Core와 같습니다.", "Core 철자로"}) {
                assertTrue(f + ": the blanket Core-spelling claim is still there: " + old, !section.contains(old));
            }
            List<String> rows = AuthGuideDocsTest.tableRows(after);
            String header = after.split("\n")[2];
            // the same column order as the first table
            assertEquals(f + ": the table header", en(f) ? "| code | Status | When auth sends it |"
                    : "| code | 상태 | 보내는 때 |", header);
            assertTrue(f + ": the first table header", section.contains(en(f) ? "\n| code | Status | Answer |\n"
                    : "\n| code | 상태 | 쓰는 답 |\n"));
            assertEquals(f + ": one row per code outside AuthErrorCode", when.length, rows.size());
            for (int i = 0; i < when.length; i++) {
                String[] cells = rows.get(i).split("\\|");
                int status = Integer.parseInt(when[i][0]);
                String code = status == 409 ? "CONFLICT" : (String) coreCode.invoke(null, status);
                assertEquals(f + " row " + (i + 1) + " code", "`" + code + "`", cells[1].trim());
                if (status != 409) { // every code but CONFLICT is Core's own for that status
                    assertEquals(f + " row " + (i + 1) + " is Core's code", StandardErrors.code(status), code);
                }
                assertEquals(f + " row " + (i + 1) + " status", when[i][0], cells[2].trim());
                for (String needle : (en(f) ? when[i][2] : when[i][1]).split(";;")) {
                    has(f + " row " + status + " when", cells[3], needle);
                }
                // the many-case cells list their cases as short items separated by semicolons
                int items = status == 400 ? 3 : status == 404 ? 4 : 1;
                assertEquals(f + " row " + status + ": semicolon-separated cases in " + cells[3], items,
                        cells[3].split("; ").length);
                assertTrue(f + " row " + status + ": the when cell narrows to a Core HttpException",
                        !cells[3].contains("Core `HttpException`)"));
                // GroupStore is read-only; only the built-in group stores (GroupGraph) throw AuthArgumentException
                assertTrue(f + " row " + status + ": a GroupStore call is said to throw",
                        !cells[3].contains("`GroupStore`"));
                // the 415 is not the global middleware's alone (PATCH bodies go to the router's error layer)
                for (String old : new String[] {"The global auth middleware answers it directly",
                    "전역 auth 미들웨어가 직접 답하므로"}) {
                    assertTrue(f + " row " + status + ": the 415 is the global middleware's alone", !cells[3].contains(old));
                }
            }
            // the PROD last error layer: coreCode(status), HTTP_<status> otherwise, so an app 409 is HTTP_409
            String rest = after.substring(after.indexOf(rows.get(rows.size() - 1)) + rows.get(rows.size() - 1).length());
            String rule = rest.trim().split("\n")[0];
            Set<String> anchors = AuthGuideDocsTest.anchors(doc);
            String errors = AuthGuideDocsTest.anchor(en(f) ? "Error responses and AuthResponses" : "오류 응답과 AuthResponses");
            assertTrue(f + ": no heading for #" + errors, anchors.contains(errors));
            has(f + " PROD rule", rule, en(f) ? "In PROD the final error layer" : "PROD의 마지막 오류 층");
            has(f + " PROD rule", rule, "](#" + errors + ")");
            has(f + " PROD rule", rule, en(f) ? "with this table's code when its status is in the table"
                    : "앱의 그 밖의 `HttpException`은 상태가 이 표에 있으면 그 코드로");
            String authFirst = en(f) ? "leaves auth exceptions (`AuthRejection`, `DuplicateUserException`, …) their own code"
                    : "auth 예외(`AuthRejection`, `DuplicateUserException`, …)는 제 코드를 지킵니다";
            String otherHttp = en(f) ? "It answers an app's other `HttpException`" : "앱의 그 밖의 `HttpException`은";
            has(f + " PROD rule", rule, authFirst);
            has(f + " PROD rule", rule, otherHttp);
            // both are HttpExceptions, so the "other HttpException" rule has to come after them
            assertTrue(HttpException.class.isAssignableFrom(AuthRejection.class));
            assertTrue(HttpException.class.isAssignableFrom(DuplicateUserException.class));
            has(f + " PROD rule", rule, en(f) ? "with `HTTP_<status>` when it is 409 or not in the table"
                    : "409이거나 표에 없으면 `HTTP_<status>`로 답합니다");
            for (String old : new String[] {"(except 409)", "(409는 제외)"}) {
                assertTrue(f + ": the PROD rule keeps the old wording " + old, !rule.contains(old));
            }
            has(f + " PROD rule", rule, en(f) ? "such a 409 is `" + coreCode.invoke(null, 409) + "`, not `CONFLICT`"
                    : "그런 409는 `CONFLICT`가 아니라 `" + coreCode.invoke(null, 409) + "`");
            assertEquals("HTTP_426", coreCode.invoke(null, 426)); // auth has no spelling for 426
            has(f + " PROD rule", rule, "`" + StandardErrors.code(426) + "`"); // Core says UPGRADE_REQUIRED
            has(f + " PROD rule", rule, "`" + coreCode.invoke(null, 426) + "`");
            has(f + " PROD rule", rule, "400–599");
            // an auth exception keeps its code; any other exception is 500 INTERNAL_ERROR
            has(f + " PROD rule", rule, "`AuthRejection`");
            has(f + " PROD rule", rule, "500 `" + coreCode.invoke(null, 500) + "`");
            // in that order: auth exceptions, the app's other HttpException, 426, the 400–599 clamp, other exceptions
            String[] order = {authFirst, otherHttp, "`" + coreCode.invoke(null, 426) + "`", "400–599",
                "500 `" + coreCode.invoke(null, 500) + "`"};
            for (int i = 1; i < order.length; i++) {
                assertTrue(f + " PROD rule: " + order[i - 1] + " before " + order[i],
                        rule.indexOf(order[i - 1]) < rule.indexOf(order[i]));
            }
            for (String old : en(f) ? new String[] {"answers an app `HttpException`", "keeps its own code, and"}
                    : new String[] {"앱의 `HttpException`을", "제 코드를 지키고, 그 밖의"}) {
                assertTrue(f + ": the PROD rule keeps the old order: " + old, !rule.contains(old));
            }
            // the AuthErrorCode bullet states what each table holds
            String bullet = null;
            for (String l : section.split("\n")) {
                if (l.startsWith("- `AuthErrorCode`")) {
                    bullet = l;
                }
            }
            assertNotNull(f + ": no AuthErrorCode bullet", bullet);
            has(f + " AuthErrorCode bullet", bullet, en(f) ? "The first table below holds only the `AuthErrorCode` codes"
                    : "첫 표는 `AuthErrorCode`의 코드만 담고");
            has(f + " AuthErrorCode bullet", bullet, en(f) ? "The second holds the codes auth sends outside this list"
                    : "둘째 표는 auth가 이 목록 밖에서");
        }
    }

    // OPS-DX-006 AC-2: 409 CONFLICT is what an admin group create with an existing id answers (driven), and the
    // "CONFLICT" literal sits at one place in the auth sources, so the "only place" sentence of the table holds
    @Test
    public void OPS_DX_006_AC2_conflictIsTheAdminGroupCreateAnswer() throws Exception {
        Auth auth = Auth.builder("0123456789abcdef0123456789abcdef-docs")
                .groups(new InMemoryGroupStore())
                .roles(RolePolicy.builder().role("groupadmin", "auth-groups:*").build())
                .build();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter("127.0.0.1", true);
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new GsonJsonCodec()).build();
        auth.install(app);
        app.listen(0);
        try {
            String token = AuthTesting.tokenFor(auth, "root", "groupadmin");
            ClientResponse first = adapter.send(ClientRequest.json("POST", "/auth/admin/groups", "{\"id\":\"eng\"}")
                    .header("Authorization", "Bearer " + token));
            assertEquals(first.text(), 201, first.status());
            ClientResponse again = adapter.send(ClientRequest.json("POST", "/auth/admin/groups", "{\"id\":\"eng\"}")
                    .header("Authorization", "Bearer " + token));
            assertEquals(again.text(), 409, again.status());
            has("the second create", again.text(), "\"code\":\"CONFLICT\"");
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
        List<String> sites = new ArrayList<>();
        collectLiteral(new File(ReadmeDocsTest.root(), "NaruServe-api/src/main/java/com/snoworca/naruserve/utils/auth"),
                "\"CONFLICT\"", sites);
        assertEquals("the auth sources that hold the \"CONFLICT\" literal",
                Collections.singletonList("AdminEndpoints.java"), sites);
    }

    // OPS-DX-006 AC-2: the 404 for an unknown id is the endpoints'; in an app route the facades return null or false,
    // and an unknown key owner or a group that does not exist is the 400 BAD_REQUEST of the row above (driven)
    @Test
    public void OPS_DX_006_AC2_unknownIdsInAnAppRouteAreNot404() throws Exception {
        InMemoryGroupStore groups = new InMemoryGroupStore();
        Auth auth = Auth.builder("0123456789abcdef0123456789abcdef-docs")
                .apiKeys(new InMemoryApiKeyStore())
                .groups(groups)
                .roles(RolePolicy.builder().role("reporter", "reports:read").build())
                .build();
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter("127.0.0.1", true);
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new GsonJsonCodec()).build();
        auth.install(app);
        app.get("/issue", (req, res) -> res.send(auth.apiKeys().issue(req, "no-such-id", "k", null, "reports:read")
                .getKey()));
        app.get("/group", (req, res) -> {
            groups.addMember("no-such-group", "u1");
            res.send("added");
        });
        app.listen(0);
        try {
            assertEquals(null, auth.users().findById("no-such-id"));
            assertEquals(null, auth.users().rename("no-such-id", "someone"));
            assertEquals(null, auth.users().update("no-such-id", u -> u));
            assertEquals(false, auth.users().delete("no-such-id"));
            assertEquals(false, auth.apiKeys().revoke("no-such-key"));
            String token = AuthTesting.tokenFor(auth, "root", "reporter");
            for (String path : new String[] {"/issue", "/group"}) {
                ClientResponse r = adapter.send(ClientRequest.of("GET", path)
                        .header("Authorization", "Bearer " + token));
                assertEquals(path + " " + r.text(), 400, r.status());
                has(path, r.text(), "\"code\":\"BAD_REQUEST\"");
            }
        } finally {
            app.shutdown(5, TimeUnit.SECONDS);
        }
    }

    /** The names of the .java files under {@code dir} holding {@code literal}, once per occurrence, in walk order. */
    private static void collectLiteral(File dir, String literal, List<String> out) throws IOException {
        File[] files = dir.listFiles();
        assertNotNull("no directory " + dir, files);
        Arrays.sort(files);
        for (File file : files) {
            if (file.isDirectory()) {
                collectLiteral(file, literal, out);
            } else if (file.getName().endsWith(".java")) {
                String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                for (int i = text.indexOf(literal); i >= 0; i = text.indexOf(literal, i + 1)) {
                    out.add(file.getName());
                }
            }
        }
    }

    // OPS-DX-006 AC-3: the RoleStore example's prose states what a failing RoleStore does
    @Test
    public void OPS_DX_006_AC3_roleStoreProseStatesTheFailureAnswers() throws IOException {
        for (String f : new String[] {KO, EN}) {
            String section = ReleaseNotesDocsTest.section(ReadmeDocsTest.read(f),
                    en(f) ? "### A database RoleStore" : "### 데이터베이스 RoleStore");
            String prose = section.substring(0, section.indexOf("```"));
            for (String needle : en(f)
                    ? new String[] {"`userCacheTtl`", "on a protected path", "guard or not", "answers 503",
                        "on a public path the request stays anonymous", "`.statelessAccess(true)`",
                        "only guards and `auth.loadUser(req)` load the principal", "`auth.loadUser(req)` returns null"}
                    : new String[] {"`userCacheTtl`", "보호 경로", "가드가 있든 없든", "503으로 답합니다",
                        "공개 경로에서는 요청이 익명으로 남습니다", "`.statelessAccess(true)`",
                        "가드와 `auth.loadUser(req)`만 주체를 읽습니다",
                        "`auth.loadUser(req)`는 null을 돌려줍니다"}) {
                has(f + " RoleStore prose", prose, needle);
            }
            String wrong = en(f) ? "makes the guarded request answer 503" : "가드가 붙은 요청이 503으로 답합니다";
            assertTrue(f + ": the RoleStore prose still limits the 503 to guarded requests", !prose.contains(wrong));
            String onlyGuards = en(f) ? "only guards load the principal" : "가드만 주체를 읽습니다";
            assertTrue(f + ": the RoleStore prose leaves auth.loadUser out of the loaders", !prose.contains(onlyGuards));
            // the RBAC table's RoleStore row agrees with the prose and links the example by its real anchor
            String row = null;
            for (String l : ReadmeDocsTest.read(f).split("\n")) {
                if (l.startsWith("| `RoleStore` (`@FunctionalInterface`")) {
                    assertTrue(f + ": two RoleStore rows", row == null);
                    row = l;
                }
            }
            assertNotNull(f + ": no RoleStore row", row);
            for (String needle : en(f)
                    ? new String[] {"an exception fails closed: 503 on a protected path, anonymous on a public one (",
                        "with `.statelessAccess(true)` only guards and `auth.loadUser(req)` load the principal (and so read the "
                            + "`RoleStore`), so an "
                            + "unguarded protected route keeps answering",
                        "[database example](#a-database-rolestore))"}
                    : new String[] {"예외는 보호 경로에서 503으로 닫힙니다(공개 경로는 익명",
                        "`.statelessAccess(true)`에서는 가드와 `auth.loadUser(req)`만 주체를(그래서 `RoleStore`를) 읽으므로 가드 없는 "
                            + "보호 경로는 그대로 답함",
                        "[데이터베이스 예제](#데이터베이스-rolestore))"}) {
                has(f + " RoleStore row", row, needle);
            }
            String flat = en(f) ? "an exception fails closed with 503" : "예외는 503으로 닫힙니다";
            assertTrue(f + ": the RoleStore row still says a flat 503", !row.contains(flat));
        }
    }

    // OPS-DX-006 AC-2: the Tokens section names the effective-lifetime getters of TokenService
    @Test
    public void OPS_DX_006_AC2_tokensSectionNamesTheLifetimeGetters() throws Exception {
        assertEquals(long.class, TokenService.class.getMethod("getAccessTtl").getReturnType());
        assertEquals(long.class, TokenService.class.getMethod("getRefreshTtl").getReturnType());
        for (String f : new String[] {KO, EN}) {
            String section = ReleaseNotesDocsTest.section(ReadmeDocsTest.read(f),
                    en(f) ? "## Tokens: TokenService and TokenPair" : "## 토큰: TokenService와 TokenPair");
            has(f + " Tokens section", section, "`getAccessTtl()`");
            has(f + " Tokens section", section, "`getRefreshTtl()`");
            has(f + " Tokens section", section, en(f) ? "in milliseconds" : "밀리초");
        }
    }

    // OPS-DX-006 AC-2: the task guide routes browser cookies, token lifetime / logout and more examples
    @Test
    public void OPS_DX_006_AC2_taskGuideHasCookieAndLifetimeRows() throws IOException {
        for (String f : new String[] {KO, EN}) {
            String doc = ReadmeDocsTest.read(f);
            String lead = en(f) ? "**Task guide:**" : "**할 일별 안내:**";
            String guide = doc.substring(doc.indexOf(lead), doc.indexOf("\n## "));
            List<String> rows = AuthGuideDocsTest.tableRows(guide);
            Set<String> anchors = AuthGuideDocsTest.anchors(doc);
            String cookie = taskRow(f, rows, en(f) ? "browser" : "브라우저");
            String[] cookieSections = en(f)
                    ? new String[] {"Auth cookies", "CSRF: cookie-mode writes", "Cross-origin clients and CORS",
                            "Origin check and trustedOrigins"}
                    : new String[] {"auth 쿠키", "CSRF: 쿠키 모드의 쓰기", "교차 출처 클라이언트와 CORS",
                            "Origin 검사와 trustedOrigins"};
            for (String heading : cookieSections) {
                links(f, cookie, anchors, heading);
            }
            String lifetime = taskRow(f, rows, en(f) ? "token lifetime" : "토큰 수명");
            has(f + " lifetime row", lifetime.toLowerCase(Locale.ROOT), en(f) ? "log out" : "로그아웃");
            for (String heading : en(f)
                    ? new String[] {"Tokens: TokenService and TokenPair", "Account endpoints: auth.routes()"}
                    : new String[] {"토큰: TokenService와 TokenPair", "계정 엔드포인트: auth.routes()"}) {
                links(f, lifetime, anchors, heading);
            }
            // the "More examples" section sits after the glossary, so the task guide points to it
            String more = taskRow(f, rows, en(f) ? "more examples" : "예제 더 보기");
            links(f, more, anchors, en(f) ? "More examples" : "더 많은 예제");
            // the sections come before and after the task guide, so neither language says "above"
            has(f + " more-examples row", more, en(f) ? "(APIs the sections describe without code)" : "(각 절이 코드 없이 설명한 API)");
            assertTrue(f + ": the more-examples row says above", !more.contains(en(f) ? "above" : "위 절"));
        }
    }

    private static String taskRow(String f, List<String> rows, String task) {
        for (String row : rows) {
            if (row.split("\\|")[1].toLowerCase(Locale.ROOT).contains(task)) {
                return row;
            }
        }
        throw new AssertionError(f + ": the task guide has no row for " + task);
    }

    private static void links(String f, String row, Set<String> anchors, String heading) {
        String anchor = AuthGuideDocsTest.anchor(heading);
        assertTrue(f + ": no heading for #" + anchor, anchors.contains(anchor));
        has(f + " task row", row, "](#" + anchor + ")");
    }

    // OPS-DX-006 AC-5: the Express cells agree with the examples: the loadUser and RoleStore rows name the
    // counterparts their examples use, and _.pick is marked as dropping what auth refuses with 400
    @Test
    public void OPS_DX_006_AC5_expressCellsMatchTheExamples() throws IOException {
        for (String f : new String[] {KO, EN}) {
            String doc = ReadmeDocsTest.read(f);
            String loadUser = express(f, doc, "| `@Nullable auth.loadUser(req)` |");
            has(f + " auth.loadUser Express cell", loadUser, "`User.findById(req.auth.sub)`");
            String roleStore = express(f, doc, "| `RoleStore` (`@FunctionalInterface`");
            has(f + " RoleStore Express cell", roleStore, "`accesscontrol`");
            // the examples' prose names the same counterparts
            has(f + " loadUser example", ReleaseNotesDocsTest.section(doc, "### auth.loadUser(req)"),
                    "`User.findById(req.auth.sub)`");
            has(f + " RoleStore example", ReleaseNotesDocsTest.section(doc,
                    en(f) ? "### A database RoleStore" : "### 데이터베이스 RoleStore"), "`accesscontrol`");
            String notDropped = en(f) ? "an unlisted name is refused with 400, not dropped"
                    : "목록에 없는 이름은 버리지 않고 400으로 거부";
            String pickRow = rowOf(f, builderRows(f), "allowedAttributes");
            String[] cells = pickRow.split("\\|");
            has(f + " .allowedAttributes Express cell", cells[cells.length - 1], "`_.pick`");
            has(f + " .allowedAttributes Express cell", cells[cells.length - 1], notDropped);
            String patch = ReleaseNotesDocsTest.section(doc, en(f) ? "### .allowedAttributes and PATCH /auth/me"
                    : "### .allowedAttributes와 PATCH /auth/me");
            String pickLine = lineStarting(f + " PATCH prose", patch, en(f) ? "Only the names" : "사용자가 `PATCH /auth/me`");
            has(f + " PATCH prose", pickLine, "`_.pick(req.body, ['displayName', 'locale'])`");
            has(f + " PATCH prose", pickLine, notDropped);
            // the apiKeyFor Java and Kotlin examples import what they use from the real packages
            String apiKey = ReleaseNotesDocsTest.section(doc, "### AuthTesting.apiKeyFor");
            String kotlin = ReadmeDocsTest.blocks(apiKey, "kotlin").get(0);
            String java = ReadmeDocsTest.blocks(apiKey, "java").get(0);
            for (Class<?> c : new Class<?>[] {Auth.class, InMemoryApiKeyStore.class, RolePolicy.class,
                AuthTesting.class}) {
                has(f + " apiKeyFor kotlin imports", kotlin, "import " + c.getName() + "\n");
                has(f + " apiKeyFor java imports", java, "import " + c.getName() + ";\n");
            }
            assertTrue(f + ": the apiKeyFor java block uses AuthUser", java.contains("AuthUser bot"));
            has(f + " apiKeyFor java imports", java, "import " + AuthUser.class.getName() + ";\n");
        }
    }

    /** The last (Express) cell of the one row starting with {@code prefix}; it must not be empty or a dash. */
    private static String express(String f, String doc, String prefix) {
        String found = null;
        for (String l : doc.split("\n")) {
            if (l.startsWith(prefix)) {
                assertTrue(f + ": two rows start with " + prefix, found == null);
                found = l;
            }
        }
        assertNotNull(f + ": no row starts with " + prefix, found);
        String[] cells = found.split("\\|");
        String cell = cells[cells.length - 1].trim();
        assertTrue(f + ": the Express cell of " + prefix + " is empty: " + cell, !cell.isEmpty() && !cell.equals("—"));
        return cell;
    }

    // OPS-DX-006 AC-4: the HTTP test recipe and the groups section intro each have an Express counterpart line
    @Test
    public void OPS_DX_006_AC4_recipeAndGroupsHaveExpressCounterparts() throws IOException {
        for (String f : new String[] {KO, EN}) {
            String doc = ReadmeDocsTest.read(f);
            String lead = en(f) ? "Express counterpart: " : "Express 대응: ";
            String recipe = ReleaseNotesDocsTest.section(doc, en(f) ? "### HTTP test recipe" : "### HTTP 테스트 레시피");
            String recipeLine = lineStarting(f + " HTTP test recipe", recipe, lead);
            has(f + " recipe Express line", recipeLine, "`supertest(app).post('/auth/login')");
            String groups = ReleaseNotesDocsTest.section(doc,
                    en(f) ? "## Groups: GroupStore and GroupLimits" : "## 그룹: GroupStore와 GroupLimits");
            String intro = groups.substring(0, groups.indexOf("\n### "));
            String groupsLine = lineStarting(f + " groups intro", intro, lead);
            has(f + " groups Express line", groupsLine, "accesscontrol");
        }
    }

    private static String lineStarting(String where, String text, String prefix) {
        for (String l : text.split("\n")) {
            if (l.startsWith(prefix)) {
                return l;
            }
        }
        throw new AssertionError(where + ": no line starts with " + prefix);
    }

    // OPS-DX-006 AC-3: the examples are appended after every earlier block, one java and one kotlin block per API,
    // in order; a later example appended at the page end does not break it
    @Test
    public void OPS_DX_006_AC3_moreExamplesAreAppendedLast() throws IOException {
        String[] apis = {"auth.authenticated()", "auth.loadUser(req)", "RoleStore", "auth.users().rename(",
            ".allowedAttributes(", "AuthTesting.apiKeyFor(", "HashCost.fixed("};
        Map<String, List<String>> newJava = new HashMap<>();
        Map<String, List<String>> newKotlin = new HashMap<>();
        for (String f : new String[] {KO, EN}) {
            String doc = ReadmeDocsTest.read(f);
            String heading = en(f) ? "## More examples" : "## 더 많은 예제";
            int at = doc.indexOf("\n" + heading + "\n");
            assertTrue(f + " has " + heading, at >= 0);
            String before = doc.substring(0, at);
            String section = doc.substring(at);
            // the intro names the packages the snippets import from
            String intro = section.substring(heading.length() + 1, section.indexOf("\n### "));
            for (String pkg : new String[] {"`com.snoworca.naruserve.utils.auth`", "`.user`", "`.password`", "`.rbac`",
                "`.apikey`", "`.testing`", "`AuthUser`", "`DuplicateUserException`", "`HashCost`"}) {
                has(f + " more-examples intro", intro, pkg);
            }
            // the guard table and the example give auth.authenticated() the same Express counterpart
            String express = "`passport.authenticate('jwt', { session: false })`";
            has(f + " auth.authenticated() row", lineStarting(f, before, "| `auth.authenticated()` |"), express);
            int ex = section.indexOf("\n### auth.authenticated()\n");
            assertTrue(f + " has the auth.authenticated() example", ex >= 0);
            has(f + " auth.authenticated() example", section.substring(ex, section.indexOf("\n```", ex)), express);
            assertEquals(f + ": java blocks before the new section", 52, ReadmeDocsTest.blocks(before, "java").size());
            assertEquals(f + ": kotlin blocks before the new section", 51,
                    ReadmeDocsTest.blocks(before, "kotlin").size());
            List<String> java = ReadmeDocsTest.blocks(section, "java");
            List<String> kotlin = ReadmeDocsTest.blocks(section, "kotlin");
            assertTrue(f + " new java blocks: " + java.size(), java.size() >= apis.length);
            assertTrue(f + " new kotlin blocks: " + kotlin.size(), kotlin.size() >= apis.length);
            for (int i = 0; i < apis.length; i++) {
                has(f + " new java block " + (i + 1), java.get(i), apis[i]);
                has(f + " new kotlin block " + (i + 1), kotlin.get(i), apis[i]);
            }
            newJava.put(f, java);
            newKotlin.put(f, kotlin);
        }
        // the English blocks are code-identical to the Korean ones: only comments may differ
        assertEquals("new java block count KO/EN", newJava.get(KO).size(), newJava.get(EN).size());
        assertEquals("new kotlin block count KO/EN", newKotlin.get(KO).size(), newKotlin.get(EN).size());
        for (int i = 0; i < newJava.get(KO).size(); i++) {
            assertEquals("new java block " + (i + 1) + " KO/EN code", ExampleCodeTokens.javaTokens(newJava.get(KO).get(i)),
                    ExampleCodeTokens.javaTokens(newJava.get(EN).get(i)));
        }
        for (int i = 0; i < newKotlin.get(KO).size(); i++) {
            assertEquals("new kotlin block " + (i + 1) + " KO/EN code",
                    ExampleCodeTokens.kotlinTokens(newKotlin.get(KO).get(i)),
                    ExampleCodeTokens.kotlinTokens(newKotlin.get(EN).get(i)));
        }
        // negative control: a one-sided change of a literal is caught, a translated comment is not
        String ko = newJava.get(KO).get(0);
        assertTrue("the comparison ignores comments", ExampleCodeTokens.javaTokens(ko + "\n// note")
                .equals(ExampleCodeTokens.javaTokens(ko + "\n// 메모")));
        assertTrue("the comparison sees code", !ExampleCodeTokens.javaTokens(ko + "\nint x = 1;")
                .equals(ExampleCodeTokens.javaTokens(ko + "\nint x = 2;")));
    }
}
