package com.snoworca.naruserve.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.Cookie;
import com.snoworca.naruserve.internal.DefaultResponse;
import com.snoworca.naruserve.testing.RecordingExchange;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** The public header value predicate and the {@code res.header} value check that uses it. */
public class HttpHeaderValuesTest {

    @Test // IR-HTTP-012 AC-1
    public void irHttp012Ac1_isFieldValueIsPublicStaticBooleanBesideIsTokenWithoutNonNull() throws Exception {
        Method token = HttpHeaderValues.class.getMethod("isToken", String.class);
        assertTrue(Modifier.isPublic(token.getModifiers()));
        assertTrue(Modifier.isStatic(token.getModifiers()));

        Method value = HttpHeaderValues.class.getMethod("isFieldValue", String.class);
        assertEquals(HttpHeaderValues.class, value.getDeclaringClass());
        assertTrue(Modifier.isPublic(value.getModifiers()));
        assertTrue(Modifier.isStatic(value.getModifiers()));
        assertEquals(boolean.class, value.getReturnType());
        assertFalse(hasNonNull(value.getDeclaredAnnotations()));
        assertFalse(hasNonNull(value.getAnnotatedReturnType().getDeclaredAnnotations()));
        assertFalse(hasNonNull(value.getParameterAnnotations()[0]));
        assertFalse(hasNonNull(value.getAnnotatedParameterTypes()[0].getDeclaredAnnotations()));
    }

    @Test // IR-HTTP-012 AC-2
    public void irHttp012Ac2_emptyAndHtabPassWhileCrLfNulDelAndU0100Fail() {
        assertTrue(HttpHeaderValues.isFieldValue(""));
        assertTrue(HttpHeaderValues.isFieldValue("a\tb"));
        assertTrue(HttpHeaderValues.isFieldValue("\t"));
        assertFalse(HttpHeaderValues.isFieldValue("a\rb"));
        assertFalse(HttpHeaderValues.isFieldValue("a\nb"));
        assertFalse(HttpHeaderValues.isFieldValue("a\u0000b"));
        assertFalse(HttpHeaderValues.isFieldValue("a\u007Fb"));
        assertFalse(HttpHeaderValues.isFieldValue("aĀb"));
    }

    @Test // IR-HTTP-012 AC-3
    public void irHttp012Ac3_printableAsciiSpaceAndU00FFPassWhileU0001AndU001FFail() {
        StringBuilder printable = new StringBuilder();
        for (char c = 0x20; c <= 0x7E; c++) {
            printable.append(c);
        }
        assertTrue(HttpHeaderValues.isFieldValue(printable.toString()));
        assertTrue(HttpHeaderValues.isFieldValue("text/plain; charset=utf-8"));
        assertTrue(HttpHeaderValues.isFieldValue("cafÿ"));
        assertFalse(HttpHeaderValues.isFieldValue("a\u0001b"));
        assertFalse(HttpHeaderValues.isFieldValue("a\u001Fb"));
    }

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_resHeaderThrowsExactlyWhenIsFieldValueIsFalseForEveryCharToU017F() {
        int rejected = 0;
        for (int code = 0x0000; code <= 0x017F; code++) {
            String value = "a" + (char) code + "b";
            boolean expected = HttpHeaderValues.isFieldValue(value);
            boolean threw = throwsIllegalArgument(value, false);
            assertEquals("res.header U+" + Integer.toHexString(code), !expected, threw);
            assertEquals("res.append U+" + Integer.toHexString(code), !expected, throwsIllegalArgument(value, true));
            if (threw) {
                rejected++;
            }
        }
        // C0 controls but HTAB, DEL, then U+0100 to U+017F
        assertEquals(0x20 - 1 + 1 + (0x017F - 0x0100 + 1), rejected);
    }

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_resStreamContentTypeThrowsExactlyWhenIsFieldValueIsFalseForEveryCharToU017F() {
        int rejected = 0;
        for (int code = 0x0000; code <= 0x017F; code++) {
            String value = "text/a" + (char) code + "b";
            boolean expected = HttpHeaderValues.isFieldValue(value);
            DefaultResponse res = new DefaultResponse(new RecordingExchange("GET", "/h"), false);
            boolean threw;
            try {
                res.stream(new ByteArrayInputStream(new byte[0]), value); // IR-HTTP-012 AC-4: startBody's check
                threw = false;
            } catch (IllegalArgumentException e) { // any other exception fails the test
                threw = true;
            }
            assertEquals("res.stream Content-Type U+" + Integer.toHexString(code), !expected, threw); // IR-HTTP-012 AC-4
            if (threw) {
                rejected++;
            }
        }
        // C0 controls but HTAB, DEL, then U+0100 to U+017F: the same count as res.header
        assertEquals(0x20 - 1 + 1 + (0x017F - 0x0100 + 1), rejected); // IR-HTTP-012 AC-4
    }

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_addCookieThrowsExactlyWhenIsFieldValueIsFalseForEveryCharToU017F() {
        // Cookie's own RFC 6265 attribute check (setPath) already refuses C0 controls, DEL, U+0080..U+009F and ';';
        // those chars never reach DefaultResponse and are only counted. U+0100..U+017F pass Cookie and must be
        // refused by addCookie's isFieldValue check.
        int cookieRejected = 0;
        int addCookieRejected = 0;
        int highBandRejectedByAddCookie = 0;
        Set<Integer> accepted = new HashSet<>();
        for (int code = 0x0000; code <= 0x017F; code++) {
            String path = "/a" + (char) code + "b";
            Cookie cookie = new Cookie("n", "v");
            try {
                cookie.setPath(path);
            } catch (IllegalArgumentException e) { // Cookie's own rule, not DefaultResponse's
                cookieRejected++;
                continue;
            }
            boolean expected = HttpHeaderValues.isFieldValue(path);
            DefaultResponse res = new DefaultResponse(new RecordingExchange("GET", "/h"), false);
            boolean threw;
            try {
                res.addCookie(cookie); // IR-HTTP-012 AC-4: addCookie's check
                threw = false;
            } catch (IllegalArgumentException e) { // any other exception fails the test
                threw = true;
            }
            assertEquals("addCookie Path U+" + Integer.toHexString(code), !expected, threw); // IR-HTTP-012 AC-4
            if (threw) {
                addCookieRejected++;
                if (code >= 0x0100) {
                    highBandRejectedByAddCookie++;
                }
            } else {
                accepted.add(code);
            }
        }
        // IR-HTTP-012 AC-4: the sweep is not vacuous; every U+0100..U+017F char got past Cookie to addCookie
        assertEquals(0x017F - 0x0100 + 1, highBandRejectedByAddCookie); // IR-HTTP-012 AC-4
        assertEquals(highBandRejectedByAddCookie, addCookieRejected); // IR-HTTP-012 AC-4
        assertTrue("accepted 'x' and U+00FF " + accepted,
                accepted.contains((int) 'x') && accepted.contains(0xFF)); // IR-HTTP-012 AC-4
        assertEquals(0x0180, cookieRejected + addCookieRejected + accepted.size()); // IR-HTTP-012 AC-4
    }

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_defaultResponseValueCheckDelegatesWithoutARuleOfItsOwn() throws Exception {
        File source = new File(System.getProperty("user.dir"),
                "src/main/java/com/snoworca/naruserve/internal/DefaultResponse.java");
        String code = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        List<String> scope = valueCheckScope(code);
        // IR-HTTP-012 AC-4: the three known value-check sites stay in scope, however badHeader() is spelled there
        assertTrue("value-check methods " + scope,
                scope.containsAll(Arrays.asList("checkHeader", "addCookie", "startBody"))); // IR-HTTP-012 AC-4
        assertEquals("no header value rule of DefaultResponse's own", new ArrayList<String>(), valueCheckViolations(code));
    }

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_methodBodiesKeysOverloadsAndSkipsBlocksAndBracesInCharLiterals() {
        String code = codeOnly(""
                + "class X {\n"
                + "    private void abort(Throwable e) { if (e != null) { log('}'); } }\n"
                + "    private void abort(Throwable e, boolean b) throws IOException { synchronized (this) { } }\n"
                + "    public static <T> List<Map.Entry<String, T>> rows(@Nullable String s) {\n"
                + "        try (InputStream in = open(\"{\")) { } catch (IOException e) { } return null; }\n"
                + "    Runnable r = new Runnable() { public void run() { } };\n"
                + "}\n");
        Map<String, String> bodies = methodBodies(code);
        assertEquals(Arrays.asList("abort#1", "abort#2", "rows", "run"), new ArrayList<>(bodies.keySet()));
        assertEquals(" if (e != null) { log('}'); } ", bodies.get("abort#1"));
        assertTrue(bodies.get("rows").endsWith("return null; "));
    }

    // ---- The AC-4 structural rule run on small inline sources, not on DefaultResponse.java ----

    /** A compliant stand-in for DefaultResponse; each case below changes one part of it. */
    private static final String SAMPLE = ""
            + "package com.snoworca.naruserve.internal;\n"
            + "import com.snoworca.naruserve.common.HttpHeaderValues;\n"
            + "public final class DefaultResponse implements Response {\n"
            + "    public static String disposition(String filename) {\n"
            + "        StringBuilder b = new StringBuilder();\n"
            + "        for (int i = 0; i < filename.length(); i++) {\n"
            + "            char c = filename.charAt(i);\n"
            + "            if (c > 0x7E) { b.append('%'); } else { b.append(c); }\n"
            + "        }\n"
            + "        return b.toString();\n"
            + "    }\n"
            + "    public static void checkHeader(String name, String value) {\n"
            + "        if (!isToken(name) || !isFieldValue(value)) {\n"
            + "            throw badHeader();\n"
            + "        }\n"
            + "    }\n"
            + "    @Override\n"
            + "    public synchronized Response addCookie(Cookie cookie) {\n"
            + "        String line = CookieSerializer.serialize(cookie);\n"
            + "        if (!isFieldValue(line)) {\n"
            + "            throw badHeader(); // a char above U+00FF, 0xFF, charAt( in a comment is not code\n"
            + "        }\n"
            + "        return this;\n"
            + "    }\n"
            + "    private static IllegalArgumentException badHeader() {\n"
            + "        return new IllegalArgumentException(\"bad header: a char above U+00FF or 255 { \");\n"
            + "    }\n"
            + "    /** The header value check; 0xFF and charAt( in a comment are not code. */\n"
            + "    static boolean isFieldValue(String s) {\n"
            + "        return com.snoworca.naruserve.common.HttpHeaderValues.isFieldValue(s);\n"
            + "    }\n"
            + "}\n";

    private static final String HELPER_CALL = "return com.snoworca.naruserve.common.HttpHeaderValues.isFieldValue(s);";
    private static final String HELPER = "    /** The header value check; 0xFF and charAt( in a comment are not code. */\n"
            + "    static boolean isFieldValue(String s) {\n"
            + "        " + HELPER_CALL + "\n"
            + "    }\n";

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_scannerAcceptsTheCompliantSample() {
        assertClean(SAMPLE);
    }

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_scannerFlagsTheOriginalPrivateLoopInTheHelper() {
        String v1 = with(SAMPLE, HELPER_CALL, ""
                + "for (int i = 0; i < s.length(); i++) {\n"
                + "            char c = s.charAt(i);\n"
                + "            if (c < 0x20 && c != '\\t' || c == 0x7F || c > 0xFF) {\n"
                + "                return false;\n"
                + "            }\n"
                + "        }\n"
                + "        return true;");
        assertFlagged(v1, "R2 isFieldValue", "R3 isFieldValue", "charAt(", "0xFF");
    }

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_scannerFlagsANamedConstantsCopyInTheHelperWhileAnotherSiteDelegates() {
        String v4 = with(SAMPLE, "if (!isFieldValue(line)) {", "if (!HttpHeaderValues.isFieldValue(line)) {");
        v4 = with(v4, "public final class DefaultResponse implements Response {\n", ""
                + "public final class DefaultResponse implements Response {\n"
                + "    private static final char SP = ' ', HTAB = '\\t';\n"
                + "    private static final int DEL = 0x7F;\n"
                + "    private static final int LATIN1_MAX = 0xFF;\n");
        v4 = with(v4, HELPER_CALL, ""
                + "for (int i = 0; i < s.length(); i++) {\n"
                + "            char c = s.charAt(i);\n"
                + "            if (c < SP && c != HTAB || c == DEL || c > LATIN1_MAX) {\n"
                + "                return false;\n"
                + "            }\n"
                + "        }\n"
                + "        return true;");
        assertFlagged(v4, "R2 isFieldValue");
    }

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_scannerIgnoresDelAndDecimalBoundsOutsideTheValueCheckMethods() {
        assertClean(with(SAMPLE, "if (c > 0x7E)", "if (c > 127)"));
        assertClean(with(SAMPLE, "if (c > 0x7E)", "if (c >= 0x7F)"));
        assertClean(with(SAMPLE, "if (c > 0x7E)", "if (c > 0xFF || c >>> 8 != 0 || c > '\\u00FF')"));
    }

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_scannerFlagsACopyInsideCheckHeaderWrittenWithALatin1CharOrAShift() {
        String loop = "        for (char c : value.toCharArray()) {\n"
                + "            if (%s) {\n"
                + "                throw badHeader();\n"
                + "            }\n"
                + "        }\n"
                + "    }\n"
                + "    @Override\n";
        String guard = "            throw badHeader();\n        }\n    }\n    @Override\n";
        String literal = with(SAMPLE, guard, "            throw badHeader();\n        }\n"
                + String.format(loop, "c > '\u00ff'"));
        assertFlagged(literal, "R3 checkHeader", "'\u00ff'", "toCharArray(");
        String escape = with(SAMPLE, guard, "            throw badHeader();\n        }\n"
                + String.format(loop, "c > '\\u00Ff'"));
        assertFlagged(escape, "R3 checkHeader", "'\\u00Ff'");
        String shift = with(SAMPLE, guard, "            throw badHeader();\n        }\n"
                + String.format(loop, "(c >>> 8) != 0"));
        assertFlagged(shift, "R3 checkHeader", ">>> 8");
    }

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_scannerFlagsCheckHeaderCallingANewHelperInsteadOfIsFieldValue() {
        String own = with(SAMPLE, "!isFieldValue(value)", "!validValue(value)");
        own = with(own, HELPER, HELPER + ""
                + "    static boolean validValue(String v) {\n"
                + "        return v.indexOf('\\r') < 0 && v.indexOf('\\n') < 0;\n"
                + "    }\n");
        assertFlagged(own, "R1 checkHeader");
        assertFlagged(with(SAMPLE, "!isFieldValue(value)", "!Rules.isFieldValue(value)"), "R1 checkHeader");
    }

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_scannerKeepsAQualifiedBadHeaderCallInScopeAndFlagsItsOwnLoop() {
        String guard = "        if (!isFieldValue(line)) {\n"
                + "            throw badHeader(); // a char above U+00FF, 0xFF, charAt( in a comment is not code\n"
                + "        }\n";
        String qualified = with(SAMPLE, guard, ""
                + "        for (int i = 0; i < line.length(); i++) {\n"
                + "            char c = line.charAt(i);\n"
                + "            if (c < 0x20 && c != '\\t' || c == 0x7F || c > 0xFF) {\n"
                + "                throw DefaultResponse.badHeader();\n"
                + "            }\n"
                + "        }\n");
        assertTrue(valueCheckScope(qualified).contains("addCookie")); // IR-HTTP-012 AC-4
        assertFlagged(qualified, "R1 addCookie", "R3 addCookie", "charAt(", "0xFF"); // IR-HTTP-012 AC-4
        String self = with(qualified, "DefaultResponse.badHeader()", "this . badHeader ()");
        assertTrue(valueCheckScope(self).contains("addCookie")); // IR-HTTP-012 AC-4
        assertFlagged(self, "R1 addCookie", "R3 addCookie"); // IR-HTTP-012 AC-4
        String reference = with(qualified, "throw DefaultResponse.badHeader();",
                "throw java.util.Optional.<IllegalArgumentException>empty().orElseGet(DefaultResponse::badHeader);");
        assertTrue(valueCheckScope(reference).contains("addCookie")); // IR-HTTP-012 AC-4
        assertFlagged(reference, "R1 addCookie", "R3 addCookie"); // IR-HTTP-012 AC-4
    }

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_scannerFlagsABareHelperBodyWithoutTheStaticImport() {
        assertFlagged(with(SAMPLE, HELPER_CALL, "return isFieldValue(s);"), "R2 isFieldValue");
        assertFlagged(with(SAMPLE, HELPER_CALL, "return HttpHeaderValues.isFieldValue(s.trim());"), "R2 isFieldValue");
        assertFlagged(with(SAMPLE, HELPER_CALL, "boolean ok = HttpHeaderValues.isFieldValue(s);\n        return ok;"),
                "R2 isFieldValue");
    }

    @Test // IR-HTTP-012 AC-4
    public void irHttp012Ac4_scannerAcceptsBehaviourPreservingSpellingsOfTheDelegation() {
        assertClean(with(SAMPLE, "static boolean isFieldValue(String s) {\n        "
                + "return com.snoworca.naruserve.common.HttpHeaderValues.isFieldValue(s);",
                "static boolean isFieldValue(final String headerValue) {\n        "
                        + "return HttpHeaderValues . isFieldValue( headerValue ) ;"));
        String staticImport = with(SAMPLE, "import com.snoworca.naruserve.common.HttpHeaderValues;\n",
                "import static com.snoworca.naruserve.common.HttpHeaderValues.isFieldValue;\n");
        assertClean(with(staticImport, HELPER, ""));
        assertClean(with(SAMPLE, "!isFieldValue(value)",
                "!com.snoworca.naruserve.common.HttpHeaderValues.isFieldValue(value)"));
        String inlined = with(SAMPLE, HELPER, "");
        inlined = with(inlined, "!isFieldValue(value)", "!HttpHeaderValues.isFieldValue(value)");
        inlined = with(inlined, "!isFieldValue(line)", "!HttpHeaderValues.isFieldValue(line)");
        assertClean(inlined);
    }

    private static String with(String source, String part, String replacement) {
        assertTrue("sample contains " + part, source.contains(part));
        return source.replace(part, replacement);
    }

    private static void assertClean(String source) {
        assertEquals(new ArrayList<String>(), valueCheckViolations(source));
    }

    private static void assertFlagged(String source, String... fragments) {
        List<String> violations = valueCheckViolations(source);
        assertFalse("flagged", violations.isEmpty());
        for (String fragment : fragments) {
            assertTrue(fragment + " in " + violations, violations.toString().contains(fragment));
        }
    }

    private static boolean throwsIllegalArgument(String value, boolean append) {
        DefaultResponse res = new DefaultResponse(new RecordingExchange("GET", "/h"), false);
        try {
            if (append) {
                res.append("X", value);
            } else {
                res.header("X", value);
            }
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /*
     * IR-HTTP-012 AC-4, structural half: one scoped rule over DefaultResponse's value-check methods, which are
     * (a) every method whose body calls badHeader() (bare, qualified, or as ::badHeader) and (b) DefaultResponse's own isFieldValue helper, if any.
     * No other method is read, so a char rule elsewhere (the percent-encoding in disposition, say) cannot trip it.
     *  R1  each (a) method calls isFieldValue: bare (the helper or a static import), qualified by
     *      [pkg.]HttpHeaderValues, or as the method reference HttpHeaderValues::isFieldValue.
     *  R2  the helper (b) is exactly `return [pkg.]HttpHeaderValues.isFieldValue(<its one parameter>);`; the bare
     *      form only under a static import of HttpHeaderValues.isFieldValue.
     *  R3  no (a) or (b) body inspects chars itself (charAt, codePointAt, toCharArray, chars, codePoints, getBytes)
     *      or names a Latin-1 bound (0xFF, 255, 0377, 0x100, 256, 0400, 'ÿ', '\377', a literal U+00FF char,
     *      >> 8, >>> 8). DEL (0x7F, 127) is not a bound here.
     * Declared limit (IR-HTTP-012 AC-4): this structural half is a heuristic text scan, not a proof. It pins the three
     * known value-check sites (checkHeader, addCookie, startBody must be in scope), requires each to call
     * isFieldValue, and flags the listed char-inspection tokens and Latin-1 bounds. It does NOT detect a copy of the
     * value rule spelled any other way: a new helper called alongside isFieldValue, String.getChars, a regex or
     * Pattern, a CharBuffer/Reader walk, or a throw that bypasses badHeader(). Growing the token list is deliberately
     * not done; each spelling would need its own rule. The guarantee that DefaultResponse applies no header-value rule
     * other than isFieldValue is behavioural: the U+0000..U+017F sweeps prove that res.header, res.append,
     * res.stream's Content-Type (startBody) and addCookie's Path (past Cookie's own RFC 6265 check) reject exactly the
     * chars isFieldValue rejects. Any differing copy fails those sweeps, and an identical copy cannot change
     * behaviour, so what this scan misses is duplicated rule text, not a wrong rule. R3 may also flag an unrelated
     * getBytes or 255/256 that a value-check method gains later; move such code into a separate method.
     */

    private static final Pattern DECLARATION = Pattern.compile(
            "(?<![\\w$.])(?:(?:public|protected|private|static|final|synchronized|abstract|native|strictfp|default)\\s+)*"
                    + "(?:<[^{};()]*>\\s*)?"
                    + "([\\w$][\\w$.]*(?:\\s*<[^{};()]*>)?(?:\\s*\\[\\s*\\])*)\\s+([\\w$]+)\\s*\\(([^()]*)\\)\\s*"
                    + "(?:throws\\s+[\\w$.,\\s]+?)?\\{");
    private static final Set<String> NOT_A_TYPE = new HashSet<>(Arrays.asList(
            "new", "return", "throw", "else", "case", "assert", "yield"));
    private static final Set<String> NOT_A_NAME = new HashSet<>(Arrays.asList(
            "if", "for", "while", "switch", "catch", "synchronized", "try", "do", "else", "return", "new", "throw",
            "super", "this"));
    /** IR-HTTP-012 AC-4: a bare, Class- or this-qualified badHeader( call, or the method reference ::badHeader. */
    private static final Pattern BAD_HEADER_CALL = Pattern.compile(
            "(?<![\\w$])badHeader\\s*\\(|::\\s*badHeader(?![\\w$])"); // IR-HTTP-012 AC-4
    private static final String QUALIFIER = "(?:[\\w$]+\\s*\\.\\s*)*HttpHeaderValues";
    /** R1: a bare or HttpHeaderValues-qualified call, or the method reference; Other.isFieldValue( is not one. */
    private static final Pattern DELEGATION = Pattern.compile("(?<![\\w$.])(?:" + QUALIFIER
            + "\\s*(?:\\.\\s*isFieldValue\\s*\\(|::\\s*isFieldValue(?![\\w$]))|isFieldValue\\s*\\()");
    private static final Pattern STATIC_IMPORT = Pattern.compile(
            "\\bimport\\s+static\\s+" + QUALIFIER + "\\s*\\.\\s*(?:isFieldValue|\\*)\\s*;");
    /** R3: own char inspection, or a Latin-1 bound in any spelling. */
    private static final Pattern OWN_INSPECTION = Pattern.compile(
            "(?<![\\w$])(?:charAt|codePointAt|toCharArray|getBytes)\\s*\\(|\\.\\s*(?:chars|codePoints)\\s*\\("
                    + "|(?<![\\w$.])(?:0[xX]0*(?:[fF]{2}(?:00)?|100)|0[bB]0*(?:1{8}|10{8})|0+(?:377|400)|255|256)"
                    + "[lL]?(?![\\w$])"
                    + "|'(?:\\\\u+0*(?:[fF]{2}|100)|\\\\0*377|ÿ|Ā)'"
                    + "|>>>?=?\\s*8(?![\\w$.])");

    /** IR-HTTP-012 AC-4: every R1, R2 and R3 violation in the value-check methods of {@code source}. */
    static List<String> valueCheckViolations(String source) {
        String code = codeOnly(source);
        boolean staticImport = STATIC_IMPORT.matcher(code).find();
        List<String> violations = new ArrayList<>();
        for (Declared d : declarations(code)) {
            boolean check = BAD_HEADER_CALL.matcher(d.body).find();
            boolean helper = d.name.equals("isFieldValue");
            if (check && !DELEGATION.matcher(d.body).find()) {
                violations.add("R1 " + d.key + ": calls badHeader() without isFieldValue");
            }
            if (helper && !delegatesOnly(d, staticImport)) {
                violations.add("R2 " + d.key + ": not exactly return HttpHeaderValues.isFieldValue(<parameter>);");
            }
            if (check || helper) {
                Matcher m = OWN_INSPECTION.matcher(d.body);
                while (m.find()) {
                    violations.add("R3 " + d.key + ": " + m.group());
                }
            }
        }
        return violations;
    }

    /** The keys of the value-check methods the rule reads. */
    static List<String> valueCheckScope(String source) {
        List<String> scope = new ArrayList<>();
        for (Declared d : declarations(codeOnly(source))) {
            if (BAD_HEADER_CALL.matcher(d.body).find() || d.name.equals("isFieldValue")) {
                scope.add(d.key);
            }
        }
        return scope;
    }

    /** Method bodies of comment- and string-free code by key: the name, or name#1, name#2... for overloads. */
    static Map<String, String> methodBodies(String code) {
        Map<String, String> bodies = new LinkedHashMap<>();
        for (Declared d : declarations(code)) {
            bodies.put(d.key, d.body);
        }
        return bodies;
    }

    private static boolean delegatesOnly(Declared helper, boolean staticImport) {
        String params = helper.params.replaceAll("<[^<>]*>", "").trim();
        while (!params.equals(params.replaceAll("<[^<>]*>", ""))) {
            params = params.replaceAll("<[^<>]*>", "");
        }
        Matcher name = Pattern.compile("([\\w$]+)\\s*(?:\\[\\s*\\]\\s*)*$").matcher(params);
        if (params.isEmpty() || params.contains(",") || !name.find()) {
            return false;
        }
        // The bare form calls the static import only when no isFieldValue(String) of DefaultResponse's own shadows
        // it; a shadowed one recurses, which the sweep above shows as a StackOverflowError.
        String target = (staticImport ? "(?:" + QUALIFIER + "\\s*\\.\\s*)?" : QUALIFIER + "\\s*\\.\\s*") + "isFieldValue";
        return helper.body.matches("\\s*return\\s+" + target + "\\s*\\(\\s*" + Pattern.quote(name.group(1))
                + "\\s*\\)\\s*;\\s*");
    }

    private static final class Declared {
        String key;
        final String name;
        final String params;
        final String body;

        Declared(String name, String params, String body) {
            this.key = name;
            this.name = name;
            this.params = params;
            this.body = body;
        }
    }

    private static List<Declared> declarations(String code) {
        List<Declared> found = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        Matcher m = DECLARATION.matcher(code);
        while (m.find()) {
            if (NOT_A_TYPE.contains(m.group(1)) || NOT_A_NAME.contains(m.group(2))) {
                continue;
            }
            int open = m.end() - 1;
            found.add(new Declared(m.group(2), m.group(3), code.substring(open + 1, closingBrace(code, open))));
            Integer n = counts.get(m.group(2));
            counts.put(m.group(2), n == null ? 1 : n + 1);
        }
        Map<String, Integer> seen = new HashMap<>();
        for (Declared d : found) {
            if (counts.get(d.name) > 1) {
                Integer n = seen.get(d.name);
                seen.put(d.name, n == null ? 1 : n + 1);
                d.key = d.name + "#" + seen.get(d.name);
            }
        }
        return found;
    }

    /** The index of the brace closing the one at {@code open}; braces in char literals do not count. */
    private static int closingBrace(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\'') {
                i++;
                while (i < code.length() && code.charAt(i) != '\'') {
                    i += code.charAt(i) == '\\' ? 2 : 1;
                }
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return code.length();
    }

    /** Java source with comments removed and string literal contents emptied; char literals are kept. */
    static String codeOnly(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && source.startsWith("//", i)) {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && source.startsWith("/*", i)) {
                int close = source.indexOf("*/", i + 2);
                i = close < 0 ? source.length() : close + 2;
                out.append(' ');
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < source.length() && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                out.append(c == '"' ? "\"\"" : source.substring(i, Math.min(j + 1, source.length())));
                i = j + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean hasNonNull(Annotation[] annotations) {
        for (Annotation a : annotations) {
            if (a.annotationType().getSimpleName().equals("NonNull")) {
                return true;
            }
        }
        return false;
    }
}
