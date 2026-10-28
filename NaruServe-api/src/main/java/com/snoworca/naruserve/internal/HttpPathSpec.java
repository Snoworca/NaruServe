package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.routing.HttpPathPattern;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** One validated, compile-once path input; decoding occurs only after a raw match. */
// @req FR-ROUTE-018 AC-7; FR-ROUTE-019 AC-1; FR-HTTP-027 AC-1 AC-2
final class HttpPathSpec {
    private final List<HttpPathPattern> patterns;
    private final boolean rootPrefix;
    HttpPathSpec(Object source,boolean end,boolean sensitive,boolean strict) {
        rootPrefix=!end && ("".equals(source) || "/".equals(source));
        List<HttpPathPattern> patterns=new ArrayList<>();
        compile(Objects.requireNonNull(source,"path"),end,sensitive,strict,patterns,
                Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>()));
        this.patterns=Collections.unmodifiableList(patterns);
    }
    private static void compile(Object source,boolean end,boolean sensitive,boolean strict,
                                List<HttpPathPattern> output,Set<Object> visiting) {
        Objects.requireNonNull(source,"path alternative");
        if(source instanceof String) {
            output.add(HttpPathPattern.compile((String)source,end,sensitive,!strict)); return;
        }
        if(!visiting.add(source)) throw new IllegalArgumentException("cyclic path alternatives");
        try {
            if(source instanceof Object[]) for(Object item:(Object[])source) compile(item,end,sensitive,strict,output,visiting);
            else if(source instanceof List<?>) for(Object item:(List<?>)source) compile(item,end,sensitive,strict,output,visiting);
            else throw new IllegalArgumentException("path must be a string or ordered alternatives: "+source.getClass().getName());
        } finally { visiting.remove(source); }
    }
    @Nullable Match match(String raw) {
        if(rootPrefix) return new Match("",Collections.emptyMap());
        for(HttpPathPattern pattern:patterns) {
            HttpPathPattern.Match match=pattern.match(raw);
            if(match==null) continue;
            Map<String,Object> decoded=new LinkedHashMap<>();
            for(Map.Entry<String,List<String>> e:match.captures().entrySet()) {
                List<String> values=new ArrayList<>();
                for(String rawValue:e.getValue()) {
                    String value=PercentDecoder.decode(rawValue,false);
                    if(value==null) throw new HttpException(400,"Failed to decode path parameter");
                    values.add(value);
                }
                decoded.put(e.getKey(),match.wildcards().contains(e.getKey())?values:values.get(0));
            }
            return new Match(match.matchedPath(),decoded);
        }
        return null;
    }
    static final class Match {
        final String matchedPath;
        final Map<String,Object> params;
        Match(String matchedPath,Map<String,Object> params) { this.matchedPath=matchedPath; this.params=params; }
    }
}
