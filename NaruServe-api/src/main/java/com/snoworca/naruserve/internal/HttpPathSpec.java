package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.routing.HttpPathPattern;
import com.snoworca.naruserve.routing.RegexPathPattern;
import java.util.*;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Validates every alternative before registration; only participating captures are decoded. */
// @req FR-ROUTE-018 AC-7; FR-ROUTE-019 AC-1 AC-2 AC-3 AC-6; FR-HTTP-027 AC-1 AC-2
final class HttpPathSpec {
    private final List<Object> patterns;
    private final boolean rootPrefix;
    HttpPathSpec(Object source,boolean end,boolean sensitive,boolean strict) {
        rootPrefix=!end && ("".equals(source) || "/".equals(source));
        List<Object> patterns=new ArrayList<>();
        compile(Objects.requireNonNull(source,"path"),end,sensitive,strict,patterns,
                Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>()));
        this.patterns=Collections.unmodifiableList(patterns);
    }
    private static void compile(Object source,boolean end,boolean sensitive,boolean strict,List<Object> output,Set<Object> visiting) {
        Objects.requireNonNull(source,"path alternative");
        if(source instanceof String) {
            String path=(String)source;
            // Express Layer loosens registration slashes before invoking the pinned compiler.
            if(!strict && !path.equals("/")) {
                int n=path.length();while(n>0 && path.charAt(n-1)=='/')n--;path=path.substring(0,n);
            }
            output.add(HttpPathPattern.compile(path,end,sensitive,!strict));return;
        }
        if(source instanceof Pattern) { output.add(RegexPathPattern.compile((Pattern)source));return; }
        if(source instanceof RegexPathPattern) { output.add(source);return; }
        if(!visiting.add(source))throw new IllegalArgumentException("cyclic path alternatives");
        try {
            if(source instanceof Object[])for(Object item:(Object[])source)compile(item,end,sensitive,strict,output,visiting);
            else if(source instanceof List<?>)for(Object item:(List<?>)source)compile(item,end,sensitive,strict,output,visiting);
            else throw new IllegalArgumentException("path must be String, Pattern, RegexPathPattern or ordered alternatives");
        }finally{visiting.remove(source);}
    }
    @Nullable Match match(String raw) {
        if(rootPrefix)return new Match("",0,0,Collections.emptyMap());
        for(Object compiled:patterns) {
            String path;int start,end;Map<String,List<String>> captures;Set<String> wildcards;
            if(compiled instanceof HttpPathPattern) {
                HttpPathPattern.Match match=((HttpPathPattern)compiled).match(raw);
                if(match==null)continue;
                path=match.matchedPath();start=0;end=path.length();captures=match.captures();wildcards=match.wildcards();
            }else{
                RegexPathPattern.Match match=((RegexPathPattern)compiled).match(raw);
                if(match==null)continue;
                path=match.matchedPath();start=match.start();end=match.end();captures=match.captures();wildcards=Collections.emptySet();
            }
            Map<String,Object> decoded=new LinkedHashMap<>();
            for(Map.Entry<String,List<String>> entry:captures.entrySet()) {
                List<String> values=new ArrayList<>();
                for(String rawValue:entry.getValue()) {
                    String value=PercentDecoder.decode(rawValue,false);
                    if(value==null)throw new PathDecodeException(rawValue);
                    values.add(value);
                }
                decoded.put(entry.getKey(),wildcards.contains(entry.getKey())?values:values.get(0));
            }
            return new Match(path,start,end,HttpParameters.ordered(decoded));
        }
        return null;
    }
    static final class Match {
        final String matchedPath;final int start,end;
        final Map<String,Object> params;
        Match(String matchedPath,int start,int end,Map<String,Object> params) {
            this.matchedPath=matchedPath;this.start=start;this.end=end;this.params=params;
        }
    }
}
