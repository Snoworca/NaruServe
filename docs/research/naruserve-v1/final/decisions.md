# Run-final decisions (loop F)

Settled by the orchestrator during the run-final loop; they bind the run report. Findings are in `loop-f/r1-v1.json`, `r1-v2.json`, `r2-v1.json` and `r2-v2.json`.

## Integration items without an acceptance-criterion tag

- I-003 (the public API of NaruServe-api uses no `java.nio.file`, `CompletableFuture` or `java.net.http`): enforced by `src/test/java/com/snoworca/naruserve/build/ClassConstraintsTest.java` (the CON-HTTP-002 signature list and `i003_noMainClassOfTheApiModuleReferencesNioFileCompletableFutureOrJavaNetHttp`), by the class-reference checks of `BuildChecksTest` and by the animal-sniffer API 21 check. No acceptance criterion carries the tag `I-003`; this decision is its mapping.
- I-015 (every server-starting test asserts that no thread is left after shutdown): enforced by the `NoLeftoverThreads` rule (`NaruServe-api/src/testFixtures/java/com/snoworca/naruserve/testing/NoLeftoverThreads.java`, watching `naruserve-*` and `tinyhttpd-*` threads), applied with `@Rule` to the TinyHttpd socket tests, by the Servlet module's own thread checks and by the isolated TinyHttpd tests. Upload temp files are checked by `SpoolCleanupTest` and `SpoolPermissionsTest`, not by the rule. This decision is its mapping.

## Left open on purpose

- 13 evidence rows of verified requirements name test methods that were renamed (FR-HTTP-004 VE-1 and VE-2, IR-HTTP-005 VE-1, VE-2 and VE-5, CON-HTTP-001 VE-1 and VE-2 and others): `edit_requirement_table_rows` is refused while a requirement is verified. Every acceptance criterion also carries a valid row (18 were added in round 1), so no behaviour is unverified.
- The FR-HTTP-004 trace link to `Dispatcher.java#invoke` (the method is `invokeRoute`) cannot be edited while the requirement is verified.
- Target Map: wave-7 reads `planned` and wave-8 reads `active` because FR-WS-004, FR-REL-002 and OPS-REL-004 stay `implemented` (`set_active_target` refuses to complete a target with a live requirement that is not verified).
- A configurer that throws part-way leaves `server(...)` and `accessLog(...)` options partly applied: verified IR-APP-007 AC-2 and IR-ENGINE-001 AC-1 state live application on one options object, so the behaviour is documented in `docs/api/app.md` and the `RestApp` Javadoc, not changed.
- Path rejections (`%2F`, `NUL`, bad `%XX`, `..`) on the default TinyHttpd adapter answer TinyHttpd's plain-text `400` without `onError`, standard JSON or an access-log line (recorded as an SDS decision in `docs/spec/06.engine-adapter.srs.md`); now also documented in `docs/api/defaults.md` and `docs/api/routing.md`.
