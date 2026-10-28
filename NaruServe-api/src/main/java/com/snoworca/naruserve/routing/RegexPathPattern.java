package com.snoworca.naruserve.routing;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** TDD compilation scaffold for the immutable Java Pattern path adapter. */
public final class RegexPathPattern {
    private RegexPathPattern() {}
    public static RegexPathPattern compile(Pattern pattern) { throw new UnsupportedOperationException("RED scaffold"); }
    public static RegexPathPattern compile(String source,int flags) { throw new UnsupportedOperationException("RED scaffold"); }
    public List<String> keys() { throw new UnsupportedOperationException("RED scaffold"); }
    public @Nullable Match match(String rawPath) { throw new UnsupportedOperationException("RED scaffold"); }
    public static final class Match {
        public String matchedPath() { throw new UnsupportedOperationException("RED scaffold"); }
        public int start() { throw new UnsupportedOperationException("RED scaffold"); }
        public int end() { throw new UnsupportedOperationException("RED scaffold"); }
        public Map<String,List<String>> captures() { throw new UnsupportedOperationException("RED scaffold"); }
    }
}
