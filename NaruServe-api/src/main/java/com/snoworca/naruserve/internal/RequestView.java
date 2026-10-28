package com.snoworca.naruserve.internal;

import java.util.*;

/** Immutable current-layer view on the single request object. */
// @req FR-HTTP-027 AC-1 AC-3 AC-4
final class RequestView {
    final String path,baseUrl;
    final Map<String,Object> params;
    RequestView(String path,String baseUrl,Map<String,?> params) {
        this.path=path; this.baseUrl=baseUrl;
        Map<String,Object> copy=new LinkedHashMap<>();
        for(Map.Entry<String,?> e:params.entrySet()) {
            Object value=e.getValue();
            if(value instanceof List<?>) value=Collections.unmodifiableList(new ArrayList<>((List<?>)value));
            copy.put(e.getKey(),value);
        }
        this.params=Collections.unmodifiableMap(copy);
    }
}
