package com.snoworca.naruserve;

/**
 * A middleware that sets itself up on the whole app: {@link RestApp#use(Middleware)} given one calls
 * {@link #install(RestApp)} instead of adding it as a plain middleware, so {@code app.use(auth)} mounts everything
 * auth needs. Express counterpart: a package whose {@code app.use(pkg)} also adds its own routes.
 */
// @req FR-AUTH-010
public interface Installable {

    /** Registers this component on {@code app}; called once by {@code app.use(this)}. */
    void install(RestApp app);
}
