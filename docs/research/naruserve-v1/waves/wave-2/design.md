# Wave 2 design — 2. Routing core

Source: frozen run design `docs/research/naruserve-v1/design/00.design.md` lines 61–126 (design lock `docs/research/naruserve-v1/design/00.design.lock.json`). GitHub issues: #2, #3. Depends on waves: wave-1.

The items below are the frozen design items of this wave, verbatim and with their run ids; this wave implements every one of them. Integration items I-001..I-021 of the run design also hold at this wave's merge.


Covers the engine-free router of issues #2 and #3; router-level parts of T01, T02, T03 and T04, with the HTTP-level assertions in section 3. Depends on sections: 1.

#### Method enum

- [D-030] `Method` SHALL be a Java enum with exactly the constants `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`, `TRACE`, `CONNECT`.
- [D-031] `Method` SHALL provide the static factories `get()`, `any()` and `of(Method...)`, each returning a `MethodSet`.
- [D-032] `Method` MUST NOT expose instance methods named after HTTP methods, so `Method.GET.delete()` does not compile.

#### MethodSet

- [D-033] `MethodSet` SHALL be immutable, with every combinator returning a new instance and leaving the receiver unchanged (T01).
- [D-034] `MethodSet` SHALL provide `get()`, `head()`, `post()`, `put()`, `patch()`, `delete()` and `options()`, each returning a new set with that method added (T01).
- [D-035] `MethodSet.except(Method)` SHALL return a new set without the given method (T02).
- [D-036] `Method.any()` SHALL contain exactly `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE` and `OPTIONS`, leaving out `TRACE` and `CONNECT`.
- [D-037] `Method.any().except(Method.DELETE)` SHALL equal the set of `GET`, `HEAD`, `POST`, `PUT`, `PATCH` and `OPTIONS` (T02).
- [D-038] `MethodSet` SHALL expose membership and an iteration in enum declaration order through accessors whose names are decided in #2.
- [D-039] Two `MethodSet` values with the same members SHALL be equal and have equal hash codes.

#### Path patterns and URL decoding

- [D-047] A route pattern SHALL consist of literal segments and `:name` variable segments, where a variable matches exactly one non-empty path segment and never crosses `/`.
- [D-048] Registering a pattern that contains Express wildcard or regular-expression syntax (`?`, `*`, `+`, parentheses) SHALL fail with a configuration error.
- [D-049] The router SHALL ignore the query string when matching.
- [D-050] Matching SHALL be case-sensitive and trailing-slash-sensitive by default, so `/Users` and `/users/` do not match `/users`.
- [D-051] The request path SHALL be percent-decoded exactly once before matching, so `%252e` reaches the router as the literal text `%2e`.
- [D-052] A request path with malformed percent-encoding, invalid UTF-8, raw non-ASCII bytes, an encoded `/`, an encoded or raw `NUL`, or any `.` or `..` segment in raw or encoded form SHALL be rejected with `400` before routing on every adapter, matching the rules TinyHttpd's parser applies before NaruServe sees the request.
- [D-053] The router SHALL support switching the handling of `//`, trailing slashes and case away from the strict default through a router option, which wave 3 exposes on `RestApp` under a name decided in #3.

#### Priority and conflicts

- [D-054] A literal segment SHALL win over a variable segment at the same position regardless of registration order (T04).
- [D-055] Pattern priority SHALL be compared segment by segment from the left, with the first differing segment deciding.
- [D-056] Registering the same method and pattern twice SHALL be reported as a configuration error when the route table is frozen, which `listen()` triggers before binding (T03).
- [D-057] Two patterns that differ only in variable names and share a method SHALL be reported as a configuration error when the route table is frozen.
- [D-058] A routing configuration error SHALL name both conflicting registrations and be raised by the route-table freeze that wave 4 runs inside `listen()` before any socket is bound.
- [D-059] The router SHALL be engine-free code inside `NaruServe-api`.

#### Router contract

- [D-389] The router SHALL store each registration with an opaque handler type parameter, so wave 2 compiles and tests without `Request`, `Response`, `RouteHandler` or `RestApp`.
- [D-390] A route lookup SHALL return an engine-free match result that is exactly one of matched (handler, path parameters, actual method, automatic-`HEAD` flag), method mismatch, automatic OPTIONS, WebSocket-only, unsupported method or no match.

#### Router results for automatic responses

- [D-060] For a path that matches no route the router SHALL return the no-match result (T06).
- [D-061] For a path that matches but has no registration for the request method, the router SHALL return the method-mismatch result carrying every method the path accepts, including automatic `HEAD` and `OPTIONS` (T02, T06).
- [D-062] For a `HEAD` request to a path with only a `GET` registration, the router SHALL return the `GET` registration marked as automatic `HEAD` (T05).
- [D-063] An explicit `HEAD` registration SHALL take precedence over automatic `HEAD` resolution in the router.
- [D-064] For an `OPTIONS` request to a path without an explicit `OPTIONS` registration, the router SHALL return the automatic-OPTIONS result carrying the accepted methods (T05).
- [D-065] An explicit `OPTIONS` registration SHALL take precedence over the automatic-OPTIONS result.
- [D-066] A request whose method token is not one of the `Method` constants SHALL yield the unsupported-method result from the router (T06).
- [D-067] `TRACE` and `CONNECT` SHALL yield the unsupported-method result and reach no route unless an explicit policy, whose semantics wave 2 decides, enables them, a policy that on the TinyHttpd adapter also governs a `CONNECT` with an origin-form or absolute-form target, because TinyHttpd's parser answers `501` before dispatch only for a `CONNECT` with an authority-form target, which is recorded as a deferred TinyHttpd gap under D-020.

#### WebSocket routes in the router

- [D-068] The router SHALL resolve a `GET` request carrying `Upgrade: websocket` against WebSocket registrations separately from `GET` registrations, so a WebSocket route and a `GET` route coexist on one path.
- [D-069] For a request without `Upgrade` to a path that has only WebSocket registrations, the router SHALL return the WebSocket-only result, which the wave 3 dispatcher answers with `426 Upgrade Required` carrying `Upgrade: websocket` and `Connection: Upgrade`, and wave 7 asserts end to end.
- [D-441] An `Upgrade: websocket` request to a path with no WebSocket registration SHALL be resolved as an ordinary `GET` without upgrading, reaching that path's `GET` registration or else the method-mismatch or no-match result, as a router test asserts for a path with only a `GET` route and a path with no route.
- [D-391] The router SHALL accept WebSocket registrations through an internal hook that marks a pattern as WebSocket, so wave 7 adds `app.ws` without changing the router.

#### Wave 2 open items

- [D-070] Wave 2 SHALL decide the (미확정) routing policy semantics owned by #3 (`//`, trailing-slash and case handling, the `TRACE`/`CONNECT` enable policy) and update `docs/api/routing.md`, removing those markers.



## Carried from loop D

Loop D ended at its round cap; the committee chose fix-and-freeze. The fixes below landed in the frozen design and this wave re-checks them in loop W:

- V2-005: New D-441: an Upgrade: websocket request to a path with no WebSocket registration is resolved as an ordinary GET without upgrading (GET route, else method mismatch or no match), with a router test. (items D-441)

## Wave decisions

These choices settle behaviour that the frozen items leave to the router's owner (issues #2 and #3) and to the implementer under the standing delegation rule. They add no new requirement; each line names the frozen items it applies. Where a choice is open, the decision follows HTTP semantics (RFC 9110) and, between equally valid options, the behaviour an Express user expects.

- Path validation before lookup (W2-003, W2-004 of rounds 1 and 2): D-052 is delivered as an engine-free path validator; the dispatcher (wave 3) and every adapter run it before the router lookup and answer `400` on rejection. The router lookup itself only returns the six results of D-390.
- Lookup result order (D-390, D-060..D-069): the lookup returns, in this order of precedence: `unsupported method` (`501`) for a method the server does not recognise or for `TRACE`/`CONNECT` while their routing is not enabled, whatever the path; `no match` (`404`) when no pattern of any route kind matches the path, which includes `OPTIONS` to an unmatched path (`OPTIONS *` stays with D-442 in wave 3); then, for a matched path: a matching route for the request method (`matched`); the automatic `OPTIONS` reply for `OPTIONS`; the `GET` route with the automatic-`HEAD` flag for `HEAD`; `WebSocket-only` (`426`) for any other request without `Upgrade` to a path whose only routes are WebSocket routes; otherwise `method mismatch` (`405`).
- Per-method priority (D-055, D-390): for the request method the router picks the most specific matching pattern among the routes of that method (static segment beats variable). With `GET /users/me` and `DELETE /users/:id`, `DELETE /users/me` matches `DELETE /users/:id`, as in Express. `method mismatch` is returned only when no route of the request method matches the path.
- `Allow` sets (D-061, D-064): the `Allow` set of `method mismatch` and of the automatic `OPTIONS` reply is the union of the methods of every route whose pattern matches the path, plus `HEAD` wherever `GET` is present and `OPTIONS` always; a WebSocket route counts as `GET`.
- Route kind (D-068, D-441): each registration carries a route kind, HTTP or WebSocket, beside its opaque handler, and a lookup takes an upgrade flag. A `GET` with `Upgrade: websocket` resolves against WebSocket routes first; when no WebSocket pattern matches the path it resolves against HTTP routes as an ordinary `GET` without upgrading (D-441). A request without `Upgrade` resolves against HTTP routes only. The matched result names the route kind, so wave 7 registers WebSocket routes through this router without changing it.
- Pattern validation (D-047, D-048): a pattern with an empty variable name (`/a/:`) or a variable name repeated within one pattern (`/a/:id/:id`) is a configuration error at registration time.
- `TRACE`/`CONNECT` policy (D-067, D-070): both are disabled by default and the lookup answers them `unsupported method`; a router policy enables each method separately; an enabled method routes like any other, is never part of `Method.any()`, and is registered only by naming it.
- Path-form policy (D-050, D-053, D-070): the default is strict — case-sensitive static segments, a trailing slash is significant, and an empty segment (`//`) never matches a variable or static segment (so it reaches `no match`). Three router policy switches relax this: merging repeated slashes, ignoring one trailing slash, and comparing static segments case-insensitively; path parameter values are always returned as received. The names of these configuration APIs are decided in wave 3 (D-398).
- Markers (D-070, D-398): wave 2 documents the decided routing semantics above in `docs/api/routing.md`; the markers that name configuration APIs and the marker about the `req.method()` value during automatic `HEAD` stay for wave 3 (D-398), because `Request` does not exist before wave 3.
- `MethodSet` accessors (D-038): wave 2 names the membership and iteration accessors, records them in `docs/api/routing.md` with Java and Kotlin examples, and keeps them free of Kotlin keywords.
- Note for wave 3 (D-422, D-442): D-442 applies to paths that reach NaruServe. TinyHttpd answers authority-form `CONNECT` (for example `CONNECT host:443`) with `501` itself and rewrites an absolute-form target to its path; the in-memory adapter mirrors both, so the adapters agree.
