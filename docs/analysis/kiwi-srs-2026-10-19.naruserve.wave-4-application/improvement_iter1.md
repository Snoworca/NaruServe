# Process B edits, iteration 1 (from eval_iter1.json)

Rules: SpecKiwi MCP only. To change ACs use `replace_acceptance_criteria` with the FULL list (read the current list with `get_requirement` first, keep every other AC text byte-identical, keep order, all unchecked). To change a statement use `edit_requirement_fields {statement}`. "append AC" = add at the end of the list.

## E1 FR-HTTP-022 (A-1-1)
- statement := "The system shall, after the global and prefix middleware ran, answer the no-match result with `404`, the method-mismatch result with `405` and an `Allow` header, the automatic-OPTIONS result with `204` and an `Allow` header, the unsupported-method result for a disabled `TRACE` or `CONNECT` (a `Method` value) with `501` and the WebSocket-only result with `426` plus `Upgrade: websocket` and `Connection: Upgrade`, where the `404`, `405`, `426` and `501` answers carry the standard error body and the `204` answers carry no body, while an unrecognised method token stays a pre-request rejection answered `501` before any `Request` is built (FR-HTTP-021)."
- AC-4 := "`TRACE /x` with `TRACE` disabled and `CONNECT /` with `CONNECT` disabled are each answered `501` with the standard error body (`code` `NOT_IMPLEMENTED`) after the global middleware ran, while a request with an unrecognised method token (FR-ROUTE-013) is answered `501` with the standard error body before any `Request` is built, as FR-HTTP-021 AC-4 states. [D-395; wave decision: 426 and 501; wave decision: Pre-request rejections; D-422] Decision: the dispatcher tells the two cases apart by whether the method token parses to a `Method` value."
- AC-8 := "A recording global middleware runs once before each of the `404`, `405`, `426`, automatic `OPTIONS` and disabled-`TRACE`/`CONNECT` `501` answers above and not for the unrecognised-token `501`, and a recording `onError` records no call for any of them. [wave decision: Pre-request rejections; residual: onError scope; D-117]"

## E2 FR-APP-003 (A-1-2)
- AC-1 := "With global middleware `g1`, `g2`, prefix middleware `p1` on `/api` registered before `g2` and `p2` on `/api`, and `app.route(\"/api/users\").use(r1).use(r2).get(h)`, a `GET /api/users` records `g1, g2, p1, p2, r1, r2, h`, and `app.get(\"/b\", r3, h2)` makes `GET /b` record `g1, g2, r3, h2`. [D-120; D-119; T35]"

## E3 FR-APP-009 (A-1-3) append AC
"The test-fixtures harness `TestApp` builds its app with `RestApp.builder().httpAdapter(inMemoryAdapter).jsonCodec(stubCodec)` (a stub `JsonCodec` in the test fixtures) and its `start()` calls `listen(0)`, which runs the route-table freeze once, so a `RoutingConfigException` of the freeze still reaches the test caller unwrapped and every wave 3 test through `TestApp` keeps passing; verified FR-HTTP-001 AC-3 (the harness builds the internal implementation and runs the freeze once) still holds. [wave-4 carried: listen runs the route-table freeze; D-154]"

## E4 FR-APP-002 (A-1-4) append AC
"The after-freeze rejection of verified FR-HTTP-008 AC-5 still holds, because the freeze runs inside `listen()` after the state left `NEW`; FR-HTTP-008 AC-6 states the wave 3 situation that D-111 ends, and its wave 3 test is re-pointed to assert that `RestApp.builder()` and `RestApp.create()` are the only public static methods of `com.snoworca.naruserve` that return a `RestApp` or its builder. [D-114; D-111; wave-4 carried: NEW-state rule]"
- AC-4 := "Every guarded method calls one internal guard method, so a test that lists by reflection all non-static `RestApp` methods returning `RestApp` (including `logger(Logger)`) and the registration methods of a `RouteBuilder` obtained from `route(String)` finds each one rejected in `RUNNING`, while `logger()`, `state()` and `route(String)` itself are not guarded. [D-114]"

## E5 (A-1-5) supersede two verified docs requirements (do OPS-ROUTE-002 first)
### E5a supersede_requirement oldId OPS-ROUTE-002, scope "ROUTE", target "wave-4", type "operational"
- title: "Decided routing semantics in docs/api/routing.md with no remaining marker (wave 4)"
- statement: "The system shall record in `docs/api/routing.md` the routing semantics decided in wave 2 and the `MethodSet` membership and iteration accessors with Java and Kotlin examples, and from wave 4 keep no (미확정) marker in it, the last one (`RouteBuilder.use(middleware)`) being decided by wave 4."
- reason: "Wave 4 decides RouteBuilder.use(middleware) (D-137), so the single remaining routing.md marker asserted by AC-3 goes; AC-1 and AC-2 restated unchanged."
- acceptanceCriteria: AC-1 and AC-2 = the current OPS-ROUTE-002 AC-1 and AC-2 texts verbatim; AC-3 := "From wave 4 no line of `docs/api/routing.md` contains `(미확정)`: the `RouteBuilder.use(middleware)` marker is replaced by the wave 4 decision of OPS-APP-001 AC-2, and the markers on the `TRACE`/`CONNECT` setting API, on the `//`/trailing-slash/case setting API and on the `req.method()` value during automatic `HEAD` stay gone; `RoutingDocsTest` is re-pointed to this requirement. [D-109; D-398; D-137]"
- then edit_requirement_fields on the successor: priority "medium", risk "low", verificationMethod "test", githubIssue "https://github.com/Snoworca/NaruServe/issues/5", tags ["wave-4","feasibility:high","routing","docs"]; add_trace_link Requirement depends_on OPS-APP-001 notes "wave 4 docs decisions"; add_trace_link Code docs/api/routing.md implements notes "trace_intent=addition_site".
### E5b supersede_requirement oldId OPS-HTTP-001, scope "HTTP", target "wave-4", type "operational"
- title: "Wave 3 decisions in docs/api with the later-wave markers kept (wave 4)"
- statement: current OPS-HTTP-001 statement with "while keeping the markers owned by other issues." replaced by "while keeping the markers owned by issues other than #4, #5, #6 and #7, whose wave 4 decisions OPS-APP-001 records."
- reason: "Wave 4 decides the markers of #5/#6/#7 (RouteBuilder.use in routing.md and express-migration markers), which AC-8 required to stay; AC-1..AC-7 restated, AC-4 re-pointed to the OPS-ROUTE-002 successor."
- acceptanceCriteria: AC-1..AC-7 = current texts verbatim except in AC-4 replace "OPS-ROUTE-002 AC-3" with "<E5a successor id> AC-3"; AC-8 := "Markers other than those this requirement and OPS-APP-001 remove stay, including `file` disposition and `redirect` in `response.md`, proxy trust, `parts()` and `jsonTree()`/`jsonMap()` in `request.md`, and the markers of `express-migration.md` owned by issues other than #4, #5, #6 and #7; `routing.md` keeps no marker once wave 4 decides `RouteBuilder.use`, and `HttpDocsTest` is re-pointed to this requirement. [wave decision: Remaining markers; D-137]"
- then edit_requirement_fields: priority "medium", risk "low", verificationMethod "test", githubIssue ".../issues/5", tags ["wave-4","feasibility:high","docs"]; add_trace_link Requirement depends_on OPS-APP-001; add_trace_link Requirement depends_on <E5a successor id>.

## E6 OPS-APP-002 (A-1-6)
- AC-1 := "The skip entries whose reason is a wave 4 method or type (`RestApp.builder()`, `RestApp.create()`, `listen`, `shutdown`, `use`, `onStart`, `onStop`, `onError`, `Middleware`, `Logger`, `accessLog`, `ApplicationContext`) are removed, or re-pointed to the later-wave member the block still needs; entries whose only reason is `RestApp.create()` are removed because the static method compiles in wave 4; the docs-examples compile test passes in `./gradlew build`. [I-008]"
- AC-2 := "A block that still needs a later-wave member (for example `res.json`, `req.body`, `app.ws`, `staticFiles` or `Cookie`) stays on the skip list with the name of that member, and the test fails for a skip entry whose block compiles. [I-008]"

## E7 IR-APP-005 (A-1-7)
- AC-6: replace "`h.port`" with "`h.port()`" (keep the rest verbatim).

## E8+E9+E10 FR-APP-012 (A-1-8, A-1-9, A-1-10)
- statement: replace "closes resources in reverse order, passes through `FAILED` to `TERMINATED`" with "closes resources in reverse order and then flushes and closes the app logger, passes through `FAILED` to `TERMINATED`".
- AC-2: append before the final tag bracket the clause ", and a `Flushable`/`AutoCloseable` app logger is flushed and closed once after the resources closed and before `TERMINATED`" (i.e. the sentence ends "... and `shutdown` returns after `listen()` ended, and a `Flushable`/`AutoCloseable` app logger is flushed and closed once after the resources closed and before `TERMINATED`."), and add "residual: Logger close on every path into TERMINATED" to its first tag bracket.
- AC-3 := "When the pending stop is recorded during `bind`, the socket bound by it is released (`stop` and `release` called) and `RUNNING` is never set, and a request that the test adapter dispatched during that `bind` is answered `503` with `Connection: close` and the standard error body with no middleware, handler or `onError` run. [D-426; D-428]"
- AC-5 := "A resource `close()` running during a failing `listen()` (state `FAILED`) starts thread B, which calls `shutdown(5, SECONDS)`, and then blocks 100 ms; B's `shutdown` returns after the state became `TERMINATED` and within its timeout; `shutdown` on a `TERMINATED` app returns at once with no callback or close run. [D-427]"

## E11 FR-APP-010 (A-1-11, A-1-25)
- statement := "The system shall, when an `onStart` callback, the freeze validation or the adapter bind fails, leave no listening socket open, never run `onStop`, move the state to `FAILED`, close the already owned and registered resources in reverse order, flush and close the app logger in the final log-writer step, move to `TERMINATED` and throw the cause from `listen()`, an unchecked cause as is and a checked cause wrapped in `AppStartException`."
- AC-3 := "A bind failure (`bind` throws `IOException`) makes `listen()` throw `AppStartException` with that cause, after which the core called the adapter's `stop` and `release`, which the in-memory adapter and the recording test adapter accept without a bound socket, so the adapter holds no socket. [D-157; D-159]"
- AC-6: append the sentence " The rotating-file-logger case is tested in `NaruServe-filelog` (FR-APP-017)." before the tag bracket? Simpler: replace the AC with "With a rotating file logger of FR-APP-017 as the app logger, no writer thread of it remains after a failed `listen()` (a test in `NaruServe-filelog`), and a console app logger that is also owned is closed once. [residual: Logger close on every path into TERMINATED; D-148; I-015]"

## E12 IR-APP-004 (A-1-12)
- AC-4 := "`register`, `registerExternal` and `own` with a `null` argument, `get`, `getOrNull` and `contains` with a `null` type, and `attribute` with a `null` key throw `NullPointerException`; the value parameter of `attribute(String, Object)` is `@Nullable` and storing a typed `null` variable (`Object v = null; ctx.attribute(key, v)`) removes the key, because a literal `null` resolves to the read overload; `attribute(key, type)` throws `ClassCastException` when the stored value is not an instance of `type`, as the request attribute store of FR-HTTP-012 does. [D-138; D-144]"
- AC-3: append the sentence " The wave 3 surface test of IR-HTTP-001 AC-3 (a statement about the wave 3 merge) is re-pointed so that `service` and `application` are expected on `Request` from wave 4." before the tag bracket (keep the tags `[D-142; I-014]`).

## E13 SEC-APP-001 (A-1-13)
- AC-1: replace the query `?token=abc&password=pw` with `?token=qzv&password=pwz` and the listed value `abc` with `qzv`, `pw` with `pwz` (keep `s3cr3t`, `c00k1e`, `pw2` -> change `pw2` to `pwq2` in both the body and the list, `token-xyz`).

## E14 FR-APP-017 (A-1-14, A-1-24) append AC
"The tests of this requirement and the failed-`listen()` writer-thread check of FR-APP-010 AC-6 live in the `NaruServe-filelog` test source set, which declares `testImplementation testFixtures(project(':NaruServe-api'))`; tests block the writer through a package-private constructor that takes the factory of the file output stream. [D-180; I-015]"
- AC-3: replace "With the writer blocked" with "With the writer blocked on a latch inside the test output stream".
FR-APP-018 append AC: "The recording `org.slf4j.Logger` is a hand-written implementation in the `NaruServe-slf4j` test source set, so the module adds no SLF4J binding to its runtime dependencies. [D-179]"

## E15 FR-APP-001 (A-1-15)
- AC-5: append " The provider classes are compiled in `src/test/java`, but their `META-INF/services/com.snoworca.naruserve.spi.AppFactory` file is not on the test class path: the test writes it into a temporary directory that only the dedicated class loader sees." before the tag bracket.

## E16 IR-APP-006 (A-1-16)
- AC-2: replace "`<ISO-8601 timestamp with offset> <LEVEL> <message>`" with "`<timestamp> <LEVEL> <message>` where the timestamp is formatted with `SimpleDateFormat(\"yyyy-MM-dd'T'HH:mm:ss.SSSZ\")` (offset like `+0900`; no `java.time`, no `X` pattern, both absent from Android API 21), checked by a test under two time zones" — keep the rest verbatim.

## E18 IR-APP-002 (A-1-18)
- AC-2: append " The wave 3 surface test of IR-HTTP-005 AC-5 (a statement about the wave 3 merge) is re-pointed to the wave 4 method set: the wave 3 methods plus those of IR-APP-002, IR-APP-003, IR-APP-005, IR-APP-006 and IR-APP-007, with `create()` and `builder()` as the only static methods." before the tag bracket.

## E21 FR-APP-013 (A-1-21) append AC
"The lifecycle gate runs before the pre-request checks, so during `STOPPING` an `OPTIONS *`, an unrecognised method token and a raw path not starting with `/` are each answered `503` with `Connection: close` and the standard error body, and in `STARTING` they are held on the start gate like any request. [wave decision: Lifecycle gate; D-428] Decision: the lifecycle gate is the first check of the dispatcher."

## E22 FR-APP-007 (A-1-22, own coverage)
- AC-6: append ", and `onStop` writes `ctx.attribute(\"k2\", v)` and reads it back" before the final period/tag.
- AC-1 := "A `UserService` registered in `onStart` is returned with identity by `req.service(UserService.class)` and `req.application().get(UserService.class)` in an HTTP handler; the WebSocket and SSE cases of D-142 are asserted in wave 7. [D-142; T41; wave decision: Services for long connections]"

## E26 CON-APP-001 (A-1-26)
- AC-1: append " The root build's class-reference check for `:NaruServe-api` gains the prefix `org/slf4j/`." before the tag bracket.

## E27 FR-APP-005 (coverage of D-136) append AC
"The error path decides by the response state at the time the error arrives (`NEW` gives the error response, committed but not completed gives an abort, `COMPLETED` stays unchanged), the rule that the `res.json` serialisation case of D-136 relies on; that case itself is asserted in wave 5 once `res.json` exists. [D-136; D-131]"

## E29 FR-APP-011
- AC-2: add "wave-4 carried: exchange lifetime" into its tag bracket (`[D-162; wave-4 carried: exchange lifetime]`).

## Not applied (recorded)
- A-1-17: FR-HTTP-004/FR-HTTP-008 are verified; their depends_on rows to FR-HTTP-002 are not edited (verified requirements are not edited outside supersede). No wave-4 requirement depends_on FR-HTTP-004 or FR-HTTP-008 (only `extends`).
- A-1-19: successor rationale cannot be set through `edit_requirement_fields`; the supersede reason and AC tags carry the coverage.
- A-1-23: AC-7 of OPS-APP-001 stays; it is checked by inspection at wave close.
