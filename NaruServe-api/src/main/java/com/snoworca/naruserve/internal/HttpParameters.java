package com.snoworca.naruserve.internal;

import java.util.*;

/** Shared HTTP captured-key enumeration and Express numeric merge rules. */
// @req FR-ROUTE-020 AC-3; FR-ROUTE-021 AC-1; FR-HTTP-027 AC-1
final class HttpParameters {
    private HttpParameters() { }
    static Map<String,Object> ordered(Map<String,?> values) {
        List<String> numeric=new ArrayList<>();
        for(String key:values.keySet())if(index(key)>=0)numeric.add(key);
        Collections.sort(numeric,(a,b)->Long.compare(index(a),index(b)));
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:numeric)result.put(key,values.get(key));
        for(Map.Entry<String,?> e:values.entrySet())if(index(e.getKey())<0)result.put(e.getKey(),e.getValue());
        return result;
    }
    private static long index(String name) {
        if(name.isEmpty() || name.length()>10 || name.length()>1 && name.charAt(0)=='0')return -1;
        long n=0;
        for(int i=0;i<name.length();i++) { char c=name.charAt(i);if(c<'0'||c>'9')return -1;n=n*10+c-'0'; }
        return n<4294967295L?n:-1;
    }
    static Map<String,Object> merge(Map<String,Object> child,Map<String,Object> parent) {
        Map<String,Object> result=new LinkedHashMap<>(parent);
        Map<String,Object> local=new LinkedHashMap<>(child);
        if(local.containsKey("0") && parent.containsKey("0")) {
            int count=0,offset=0;
            while(local.containsKey(Integer.toString(count)))count++;
            while(parent.containsKey(Integer.toString(offset)))offset++;
            for(int i=count-1;i>=0;i--) {
                local.put(Integer.toString(i+offset),local.get(Integer.toString(i)));
                if(i<offset)local.remove(Integer.toString(i));
            }
        }
        result.putAll(local);return ordered(result);
    }
    @SuppressWarnings("unchecked") static List<String> values(Object value) {
        return value instanceof String?Collections.singletonList((String)value):(List<String>)value;
    }
}
