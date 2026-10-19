# Process B edits, iteration 2 (from eval_iter2.json)

Same rules as iteration 1: MCP only; full AC lists for replace_acceptance_criteria (current texts with the "AC-n: " prefix stripped, unchanged ACs byte-identical, all unchecked, no "AC-n:" prefix); statements through edit_requirement_fields.

## F1 FR-APP-009 (A-2-1)
- In the AC that starts "The test-fixtures harness `TestApp` builds its app", replace "and every wave 3 test through `TestApp` keeps passing;" with "and every wave 3 test through `TestApp` that cites a requirement wave 4 does not supersede keeps passing unchanged, while the tests citing FR-HTTP-002, FR-HTTP-003, FR-HTTP-005, FR-HTTP-006 or FR-HTTP-010 are re-pointed to FR-HTTP-021..FR-HTTP-025 and assert the standard error body (a requirement change, not a weakened test);".

## F2 FR-APP-009 append AC (A-2-2)
"The start gate and the lifecycle gate belong to the exchange handler that `listen()` passes to the adapter; the wave 3 `Dispatcher(Router)` constructor and `DefaultRestApp.dispatcher()` that unit tests bind without `listen()` stay ungated, so those verified wave 3 unit tests keep dispatching. [D-428; D-156] Decision: the gates wrap the dispatcher instead of living inside it."
## F2b FR-APP-002
- In the AC that starts "The after-freeze rejection of verified FR-HTTP-008 AC-5 still holds", replace "because the freeze runs inside `listen()` after the state left `NEW`;" with "because the one guard rejects when the state is not `NEW` or the route table is frozen (the harness and the FR-HTTP-008 tests freeze while the state is still `NEW`);".

## F3 FR-HTTP-021 (A-2-3)
- statement: replace "The system shall provide a dispatcher that takes each exchange from an `HttpAdapter`, checks the asterisk-form" with "The system shall provide a dispatcher that takes each exchange from an `HttpAdapter` that passed the lifecycle gate of FR-APP-009 and FR-APP-013, checks the asterisk-form".
- AC-2: append at its end " This order applies to requests that passed the lifecycle gate; the unrecognised-method-token `501` comes after the validator check and before a `Request` is built."

## F4 supersede verified OPS-HTTP-002 (A-2-4)
supersede_requirement oldId OPS-HTTP-002, scope "HTTP", target "wave-4", type "operational"
- title: "docs/api Java examples compile against the API of the current wave (wave 4)"
- statement: "The system shall compile, in the root test suite, every ```java block of `docs/api/*.md` whose methods and types exist at the merge of the current wave, against the `NaruServe-api` main classes, skipping only blocks on an explicit, non-stale skip list (I-008, I-009)."
- reason: "Wave 4 docs blocks use TimeUnit, spi types and app-defined example types, so the fixed import set (AC-3) and the skip reasons (AC-4) grow; AC-1, AC-2, AC-5, AC-6, AC-7 restated unchanged."
- acceptanceCriteria: AC-1, AC-2, AC-5, AC-6, AC-7 = the current OPS-HTTP-002 texts verbatim; AC-3 = the current AC-3 text with "the fixed imports `com.snoworca.naruserve.*`, `com.snoworca.naruserve.routing.*`, `java.util.*` and `java.io.*`" replaced by "the fixed imports `com.snoworca.naruserve.*`, `com.snoworca.naruserve.routing.*`, `com.snoworca.naruserve.spi.*`, `java.util.*`, `java.util.concurrent.*` and `java.io.*`" and with "and stub values the docs name (such as `getUser`, `id` or `user`)" replaced by "and stub values the docs name (such as `getUser`, `id`, `user`, `ctx`, `auth`, `myAdapter` or `myCodec`)" (keep the rest, including the Decision and SDS tags, verbatim); AC-4 = the current AC-4 text with "or a type of a later wave or of a library absent from the root test classpath (named, such as Gson `@SerializedName`, `Logger`, `JsonCodec`, `UploadFile` or `Middleware`)" replaced by "a type of a later wave or of a library absent from the root test classpath (named, such as Gson `@SerializedName` or `UploadFile`), or an app-defined example type the block assumes (named, such as `UserService` or `Database`)" (rest verbatim).
- then edit_requirement_fields on the successor: priority "medium", risk "low", verificationMethod "test", githubIssue "https://github.com/Snoworca/NaruServe/issues/5", tags ["wave-4","feasibility:high","docs"]; add_trace_link Code "src/test/java/com/snoworca/naruserve/docs/DocsExamplesCompileTest.java" implements "trace_intent=addition_site"; add_trace_link Requirement extends IR-HTTP-005 "compiles against the RestApp surface".
- then on OPS-APP-002: add_trace_link Requirement extends <new successor id> notes "docs compile gate (wave 4 successor)".

## F5 OPS-APP-001 (A-2-5)
- add_trace_link Requirement extends OPS-HTTP-003 notes "wave 3 docs decisions (wave 4 successor)".

## F6 OPS-HTTP-003 (A-2-6) add trace links
- Code rows, relation "implements", notes "trace_intent=verifies": docs/api/request.md, docs/api/response.md, docs/api/routing.md, docs/api/app.md, docs/api/defaults.md, docs/api/express-migration.md.
- Code row NaruServe-api/src/test/java/com/snoworca/naruserve/HttpDocsTest.java relation "verifies" notes "trace_intent=verifies".
- Requirement rows relation "depends_on": IR-HTTP-005, FR-HTTP-014, FR-HTTP-012, FR-HTTP-009 (notes "documented API"). If add_trace_link refuses one because it is not verified, skip it and report.

## F7 OPS-APP-001 AC-6 (A-2-7)
- AC-6: append before its tag bracket: " The `express.Router()` and `cors()` markers of `express-migration.md` stay (not decided in wave 4), and the lines of `app.md` that mix a wave 4 marker with a `ws` marker are split so that the `ws` return-type and `ws` callback-name markers stay while the route-middleware and `onStart`/`onStop`/`onError` parts are decided."

## F8 FR-HTTP-025 AC-3 (A-2-8)
- AC-3: append at its end " The query is parsed after the route middleware ran and before the handler; a request without a matched route gets the fallback answer (`404`, `405`, ...) and its query is parsed only if a middleware reads it."

## F9 FR-APP-016 AC-3 (A-2-9)
- AC-3: append before its tag bracket: " The case without `android.util.Log` loads `LogcatLogger` through a dedicated class loader that sees the `NaruServe-logcat` and `NaruServe-api` main classes but not the test classes."

## F10 FR-APP-018 AC-2 (A-2-10)
- AC-2 := "`new Slf4jLogger()` delegates to the SLF4J logger named `NaruServe`, checked through a hand-written test-scope `org.slf4j.impl.StaticLoggerBinder` whose `ILoggerFactory` records the requested names (slf4j-api 1.7.36 binding mechanism), which leaves the runtime dependencies binding-free, and `new Slf4jLogger(null)` throws `NullPointerException`. [D-179]"

## F11 FR-APP-012 AC-2 (A-2-11)
- AC-2: append at its end " Decision: with a pending stop recorded during `onStart` callback k, the callbacks registered after k do not run; the latch test registers a second callback and asserts it never ran."
