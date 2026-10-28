package com.snoworca.naruserve.parity;
import com.google.gson.JsonObject;
import java.util.*;
/** Real Java scenario runner; never derives an outcome from reference observations. */
final class RuntimeReplay {
    static final class Observation {
        final JsonObject value;
        final Throwable registrationFailure;
        final List<Throwable> errors;
        Observation(JsonObject value, Throwable registrationFailure, List<Throwable> errors) {
            this.value=value;this.registrationFailure=registrationFailure;this.errors=errors;
        }
    }
    static Observation run(JsonObject reference) throws Exception {
        throw new UnsupportedOperationException("OPS-ROUTE-004 AC-3: actual Java replay not implemented");
    }
}
