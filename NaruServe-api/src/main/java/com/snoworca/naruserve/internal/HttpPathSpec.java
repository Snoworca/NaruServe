package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.routing.HttpPathPattern;
import com.snoworca.naruserve.routing.RegexPathPattern;
import java.util.*;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Validates every alternative before registration; only participating captures are decoded. */
// @req FR-ROUTE-018 AC-7; FR-ROUTE-019 AC-1 AC-2 AC-3 AC-6; FR-HTTP-027 AC-1 AC-2
final class HttpPathSpec {
    private final List<Object> patterns,sources;
    private final boolean rootPrefix,end,mergeSlashes;
    HttpPathSpec(Object source,boolean end,boolean sensitive,boolean strict) {
        this(source,end,sensitive,strict,null);
    }
    private HttpPathSpec(Object source,boolean end,boolean sensitive,boolean strict,
                         com.snoworca.naruserve.routing.RouterPolicy policy) {
        this.end=end;this.mergeSlashes=policy!=null && policy.mergeSlashes();
        List<Object> originals=new ArrayList<>();
        collect(Objects.requireNonNull(source,"path"),originals,Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>()));
        this.sources=Collections.unmodifiableList(originals);
        rootPrefix=!end && originals.size()==1 && ("".equals(originals.get(0)) || "/".equals(originals.get(0)));
        List<Object> compiled=new ArrayList<>();
        for(Object item:originals) {
            if(item instanceof String) {
                String path=(String)item;
                if(mergeSlashes)path=mergePatternSlashes(path);
                if(!strict && !path.equals("/")) {
                    int n=path.length();
                    if(policy==null || !end)while(n>0 && path.charAt(n-1)=='/')n--;
                    else if(n>0 && path.charAt(n-1)=='/')n--;
                    path=path.substring(0,n);
                }
                compiled.add(HttpPathPattern.compile(path,end,sensitive,!strict));
            } else if(item instanceof Pattern)compiled.add(RegexPathPattern.compile((Pattern)item));
            else compiled.add(item);
        }
        patterns=Collections.unmodifiableList(compiled);
    }
    HttpPathSpec withPolicy(com.snoworca.naruserve.routing.RouterPolicy policy) {
        return new HttpPathSpec(sources,end,!policy.caseInsensitive(),end && !policy.ignoreTrailingSlash(),policy);
    }
    private static void collect(Object source,List<Object> output,Set<Object> visiting) {
        Objects.requireNonNull(source,"path alternative");
        if(source instanceof String || source instanceof Pattern || source instanceof RegexPathPattern){output.add(source);return;}
        if(!visiting.add(source))throw new IllegalArgumentException("cyclic path alternatives");
        try {
            if(source instanceof Object[])for(Object item:(Object[])source)collect(item,output,visiting);
            else if(source instanceof List<?>)for(Object item:(List<?>)source)collect(item,output,visiting);
            else throw new IllegalArgumentException("path must be String, Pattern, RegexPathPattern or ordered alternatives");
        }finally{visiting.remove(source);}
    }
    /** Preserve quoted parameter names and escaped grammar characters while merging literal separators. */
    private static String mergePatternSlashes(String source) {
        StringBuilder result=new StringBuilder();boolean quoted=false,slash=false;
        for(int i=0;i<source.length();i++) {
            char ch=source.charAt(i);
            if(ch=='\\' && i+1<source.length()) { result.append(ch).append(source.charAt(++i));slash=false;continue; }
            if(ch=='"')quoted=!quoted;
            if(ch=='/' && !quoted) { if(!slash)result.append(ch);slash=true; }
            else { result.append(ch);slash=false; }
        }
        return result.toString();
    }
    private static final class RawPath {
        final String value;final int[] offsets;
        RawPath(String raw) {
            StringBuilder value=new StringBuilder();int[] offsets=new int[raw.length()+1];int n=0;
            for(int i=0;i<raw.length();i++) {
                char ch=raw.charAt(i);value.append(ch);
                if(ch=='/')while(i+1<raw.length() && raw.charAt(i+1)=='/')i++;
                offsets[++n]=i+1;
            }
            this.value=value.toString();this.offsets=offsets;
        }
    }
    @Nullable Match match(String raw) {
        if(rootPrefix)return new Match("",0,0,Collections.emptyMap());
        RawPath normalized=mergeSlashes?new RawPath(raw):null;
        for(Object compiled:patterns) {
            String path;int start,end;Map<String,List<String>> captures;Set<String> wildcards;
            if(compiled instanceof HttpPathPattern) {
                HttpPathPattern.Match match=((HttpPathPattern)compiled).match(normalized==null?raw:normalized.value);
                if(match==null)continue;
                path=match.matchedPath();start=0;end=path.length();captures=match.captures();wildcards=match.wildcards();
                if(normalized!=null){end=normalized.offsets[end];path=raw.substring(0,end);}
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
