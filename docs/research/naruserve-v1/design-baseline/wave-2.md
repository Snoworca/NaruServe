## 2. Routing core

Covers the engine-free router of issues #2 and #3; router-level parts of T01, T02, T03 and T04, with the HTTP-level assertions in section 3. Depends on sections: 1.

### Method enum

- [D-030] `Method` SHALL be a Java enum with exactly the constants `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`, `TRACE`, `CONNECT`.
- [D-031] `Method` SHALL provide the static factories `get()`, `any()` and `of(Method...)`, each returning a `MethodSet`.
- [D-032] `Method` MUST NOT expose instance methods named after HTTP methods, so `Method.GET.delete()` does not compile.

### MethodSet

- [D-033] `MethodSet` SHALL be immutable, with every combinator returning a new instance and leaving the receiver unchanged (T01).
- [D-034] `MethodSet` SHALL provide `get()`, `head()`, `post()`, `put()`, `patch()`, `delete()` and `options()`, each returning a new set with that method added (T01).
- [D-035] `MethodSet.except(Method)` SHALL return a new set without the given method (T02).
- [D-036] `Method.any()` SHALL contain exactly `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE` and `OPTIONS`, leaving out `TRACE` and `CONNECT`.
- [D-037] `Method.any().except(Method.DELETE)` SHALL equal the set of `GET`, `HEAD`, `POST`, `PUT`, `PATCH` and `OPTIONS` (T02).
- [D-038] `MethodSet` SHALL expose membership and an iteration in enum declaration order through accessors whose names are decided in #2.
- [D-039] Two `MethodSet` values with the same members SHALL be equal and have equal hash codes.

### Path patterns and URL decoding

- [D-047] A route pattern SHALL consist of literal segments and `:name` variable segments, where a variable matches exactly one non-empty path segment and never crosses `/`.
- [D-048] Registering a pattern that contains Express wildcard or regular-expression syntax (`?`, `*`, `+`, parentheses) SHALL fail with a configuration error.
- [D-049] The router SHALL ignore the query string when matching.
- [D-050] Matching SHALL be case-sensitive and trailing-slash-sensitive by default, so `/Users` and `/users/` do not match `/users`.
- [D-051] The request path SHALL be percent-decoded exactly once before matching, so `%252e` reaches the router as the literal text `%2e`.
- [D-052] A request path with malformed percent-encoding, invalid UTF-8, raw non-ASCII bytes, an encoded `/`, an encoded or raw `NUL`, or any `.` or `..` segment in raw or encoded form SHALL be rejected with `400` before routing on every adapter, matching the rules TinyHttpd's parser applies before NaruServe sees the request.
- [D-053] The router SHALL support switching the handling of `//`, trailing slashes and case away from the strict default through a router option, which wave 3 exposes on `RestApp` under a name decided in #3.

### Priority and conflicts

- [D-054] A literal segment SHALL win over a variable segment at the same position regardless of registration order (T04).
- [D-055] Pattern priority SHALL be compared segment by segment from the left, with the first differing segment deciding.
- [D-056] Registering the same method and pattern twice SHALL be reported as a configuration error when the route table is frozen, which `listen()` triggers before binding (T03).
- [D-057] Two patterns that differ only in variable names and share a method SHALL be reported as a configuration error when the route table is frozen.
- [D-058] A routing configuration error SHALL name both conflicting registrations and be raised by the route-table freeze that wave 4 runs inside `listen()` before any socket is bound.
- [D-059] The router SHALL be engine-free code inside `NaruServe-api`.

### Router contract

- [D-389] The router SHALL store each registration with an opaque handler type parameter, so wave 2 compiles and tests without `Request`, `Response`, `RouteHandler` or `RestApp`.
- [D-390] A route lookup SHALL return an engine-free match result that is exactly one of matched (handler, path parameters, actual method, automatic-`HEAD` flag), method mismatch, automatic OPTIONS, WebSocket-only, unsupported method or no match.

### Router results for automatic responses

- [D-060] For a path that matches no route the router SHALL return the no-match result (T06).
- [D-061] For a path that matches but has no registration for the request method, the router SHALL return the method-mismatch result carrying every method the path accepts, including automatic `HEAD` and `OPTIONS` (T02, T06).
- [D-062] For a `HEAD` request to a path with only a `GET` registration, the router SHALL return the `GET` registration marked as automatic `HEAD` (T05).
- [D-063] An explicit `HEAD` registration SHALL take precedence over automatic `HEAD` resolution in the router.
- [D-064] For an `OPTIONS` request to a path without an explicit `OPTIONS` registration, the router SHALL return the automatic-OPTIONS result carrying the accepted methods (T05).
- [D-065] An explicit `OPTIONS` registration SHALL take precedence over the automatic-OPTIONS result.
- [D-066] A request whose method token is not one of the `Method` constants SHALL yield the unsupported-method result from the router (T06).
- [D-067] `TRACE` and `CONNECT` SHALL yield the unsupported-method result and reach no route unless an explicit policy, whose semantics wave 2 decides, enables them, a policy that on the TinyHttpd adapter also governs a `CONNECT` with an origin-form or absolute-form target, because TinyHttpd's parser answers `501` before dispatch only for a `CONNECT` with an authority-form target, which is recorded as a deferred TinyHttpd gap under D-020.

### WebSocket routes in the router

- [D-068] The router SHALL resolve a `GET` request carrying `Upgrade: websocket` against WebSocket registrations separately from `GET` registrations, so a WebSocket route and a `GET` route coexist on one path.
- [D-069] For a request without `Upgrade` to a path that has only WebSocket registrations, the router SHALL return the WebSocket-only result, which the wave 3 dispatcher answers with `426 Upgrade Required` carrying `Upgrade: websocket` and `Connection: Upgrade`, and wave 7 asserts end to end.
- [D-441] An `Upgrade: websocket` request to a path with no WebSocket registration SHALL be resolved as an ordinary `GET` without upgrading, reaching that path's `GET` registration or else the method-mismatch or no-match result, as a router test asserts for a path with only a `GET` route and a path with no route.
- [D-391] The router SHALL accept WebSocket registrations through an internal hook that marks a pattern as WebSocket, so wave 7 adds `app.ws` without changing the router.

### Wave 2 open items

- [D-070] Wave 2 SHALL decide the (미확정) routing policy semantics owned by #3 (`//`, trailing-slash and case handling, the `TRACE`/`CONNECT` enable policy) and update `docs/api/routing.md`, removing those markers.

