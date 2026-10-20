# Carried into wave 3 (from wave 2 loop W)

- D-442 applies to paths that reach NaruServe. TinyHttpd answers authority-form `CONNECT` (for example `CONNECT host:443`) with `501` itself and rewrites an absolute-form target to its path before dispatch; the in-memory adapter mirrors both so the adapters agree (I-021).
- The dispatcher and every adapter run the wave 2 path validator before the router lookup and answer `400` on rejection; the validator does not decode.
- The `req.method()` value during automatic `HEAD` and the routing-policy configuration API names are decided in wave 3 (D-398).
