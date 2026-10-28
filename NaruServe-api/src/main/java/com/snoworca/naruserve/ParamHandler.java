package com.snoworca.naruserve;

import java.util.List;

/** Preprocesses one present decoded parameter. Scalars are singleton values; wildcards retain segments. */
// @req FR-ROUTE-021 AC-1 AC-4
@FunctionalInterface
public interface ParamHandler {
    void handle(Request request,Response response,Next next,List<String> value,String name) throws Exception;
}
