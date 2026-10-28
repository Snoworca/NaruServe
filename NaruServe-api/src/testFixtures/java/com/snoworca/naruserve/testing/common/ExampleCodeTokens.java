package com.snoworca.naruserve.testing.common;

import java.util.ArrayList;
import java.util.List;

/** Literal-preserving lexical comparison for bilingual documentation examples, never a compiler substitute. */
// @req OPS-REL-008 AC-3
public final class ExampleCodeTokens {
    private ExampleCodeTokens() { }
    private static final String[] OPERATORS = {
        ">>>=", "<<=", ">>=", "===", "!==", "...", ">>>", "..<", "::", "->", "++", "--",
        "&&", "||", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
        "<<", ">>", "..", "?.", "?:", "!!"
    };

    public static List<String> javaTokens(String source) { return tokens(source, false); }
    public static List<String> kotlinTokens(String source) { return tokens(source, true); }

    private static List<String> tokens(String source, boolean newlines) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < source.length();) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                if (newlines && (c == '\n' || c == '\r') && !out.isEmpty()
                        && !out.get(out.size() - 1).equals("\n")) out.add("\n");
                i++; continue;
            }
            if (source.startsWith("//", i)) {
                while (i < source.length() && source.charAt(i) != '\n' && source.charAt(i) != '\r') i++;
                continue;
            }
            if (source.startsWith("/*", i)) {
                int depth = 1; i += 2;
                while (i < source.length() && depth > 0) {
                    if (newlines && source.startsWith("/*", i)) { depth++; i += 2; }
                    else if (source.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                if (depth != 0) throw new IllegalArgumentException("unterminated example comment");
                continue;
            }
            int start = i;
            if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                if (end < 0) throw new IllegalArgumentException("unterminated example raw string");
                i = end + 3;
            } else if (c == '\"' || c == '\'' || c == '`') {
                i++; boolean closed = false;
                while (i < source.length()) {
                    char next = source.charAt(i++);
                    if (next == '\\' && c != '`' && i < source.length()) i++;
                    else if (next == c) { closed = true; break; }
                }
                if (!closed) throw new IllegalArgumentException("unterminated example literal");
            } else if (Character.isJavaIdentifierStart(c)) {
                i++;
                while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
            } else if (Character.isDigit(c)) {
                i++;
                while (i < source.length() && (Character.isJavaIdentifierPart(source.charAt(i))
                        || source.charAt(i) == '.' && !source.startsWith("..", i))) i++;
            } else {
                String operator = null;
                for (String candidate : OPERATORS) if (source.startsWith(candidate, i)) { operator = candidate; break; }
                i += operator == null ? 1 : operator.length();
            }
            out.add(source.substring(start, i));
        }
        if (!out.isEmpty() && out.get(out.size() - 1).equals("\n")) out.remove(out.size() - 1);
        return out;
    }
}
