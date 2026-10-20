exec(open('fix3.py',encoding='utf-8').read())
out.clear()
# FR-KT-004
ac_sub('FR-KT-004',2,"Each marked test function contains","Each marked test function (its extent runs from its marker line to the next marker line or the end of the file) contains")
ac('FR-KT-004',5,"Each block runs in exactly one module with exactly one marker: blocks that use the `NaruServe-kotlin` extension (`req.body<User>()`) run in `NaruServe-kotlin`'s tests against the module extension of IR-KT-002, the other blocks run where the wave 5 and 7 docs Kotlin tests already hold them (`JsonDocsKotlinTest`, `SseDocsKotlinTest`, `WebSocketDocsKotlinTest` gain the markers), and only the test-local copy of `body()` in `JsonDocsKotlinTest` (wave 5, OPS-JSON-001 AC-5) stays untouched. [D-349; D-354]")
# CON-KT-001
stm('CON-KT-001',"and a build check fails the build when either appears.","and the build check `checkNoCoroutines` fails the build when either appears.")
ac_sub('CON-KT-001',1,"A build check wired into `check`","The build check `checkNoCoroutines`, wired into `check`,")
ac('CON-KT-001',3,"A negative fixture proves both parts as Gradle TestKit builds that need no coroutines artifact from the network: a stub source `kotlinx/coroutines/BuildersKt.java` whose class is referenced from a fixture main class, and a fixture dependency on a `kotlinx-coroutines-core` POM served from a local file repository, each make `checkNoCoroutines` fail naming the class or the artifact, so the check cannot pass vacuously. [D-356]")
# FR-SVL-003
stm('FR-SVL-003',"never adds, removes or changes `Content-Length`,","never itself adds, removes or changes `Content-Length` (its own code never calls `setContentLength`; a container may still frame a small buffered body with a length),")
ac_sub('FR-SVL-003',4,"with `sendFile` copying in chunks of at most 64 KiB and never reading the file into memory;","with `sendFile` copying in chunks of at most 64 KiB, which a spy output stream records, and never reading the file into memory;")
# FR-SVL-005
ac('FR-SVL-005',3,"The servlet is registered with `asyncSupported=true`; a test filter in front of the servlet records, after the handler called `res.sse()`, that `request.getAsyncContext().getTimeout()` is `0` for every stream; and a stream kept open longer than the container's default async timeout (the test lowers it to 1 s) still receives a heartbeat after 2.5 s and is not ended by the container. [r4 W2-005]")
# FR-SVL-006
ac('FR-SVL-006',3,"A stream whose client stopped reading is cut at the deadline: `stop` returns within the deadline plus 1 s and the stream's `AsyncContext` is completed, so the container closes the output (Jetty frees a blocked writer at once, another container at its own write timeout, which the test does not wait for); the deviation sentence is present in `docs/api/servlet.md` and `docs/api/sse.md` gains a pointer to it. [r5 W2-001]")
# CON-SVL-001
ac('CON-SVL-001',4,"The signature comparison test of IR-HTTP-007 AC-4 passes unchanged and removes or changes no public signature of `RestApp`, `Request`, `Response` or `HttpAdapter`; an empty `git diff --stat` of `NaruServe-api/src/main/java/com/snoworca/naruserve/spi` against the wave 7 merge is recorded as inspection evidence; the TinyHttpd checkout shows no modified file (CON-BUILD-010). [I-006; I-014; D-106]")
# FR-REL-002
stm('FR-REL-002',"an OWASP Dependency-Check scan of the runtime dependencies of the published modules,","an OWASP Dependency-Check scan, run in a child Gradle invocation whose exit value is ignored, of the runtime dependencies of the published modules,")
rat('FR-REL-002',"Pitfall: Dependency-Check fails the build on an update error by default;","Measured by prototype (2026-10-20, feasibility run): Dependency-Check 13.0.0 aborts with \"Analysis failed\" even with `failOnError=false`, so a failure inside the same build cannot be turned into a record; `releaseScan` therefore runs the scan in a child Gradle invocation with `ignoreExitValue` and writes `pending` on a non-zero exit or a missing report. Pitfall: Dependency-Check fails the build on an update error by default;")
ac_add('FR-REL-002',"`releaseScan` runs the scanner in a child Gradle invocation with `ignoreExitValue` and writes the `pending` report when that invocation exits non-zero or leaves no report file, which the report writer's test covers with a stub exit code. [D-376; r3 W2-005]")
# FR-REL-004
rat('FR-REL-004',"Pitfall: a dependency whose POM states two licenses","Pitfall: the `slf4j-api` 1.7.36 POM has no `<licenses>` element; its MIT license is declared in the parent POM, so the resolution walks the parent POM chain before it reports a missing license. Pitfall: a dependency whose POM states two licenses")
ac_sub('FR-REL-004',2,"fails for GPL-2.0, LGPL-3.0 and AGPL-3.0 and for an unknown license,","fails for GPL-2.0, LGPL-3.0 and AGPL-3.0 and for an unknown license after the parent POM chain was searched (a license declared only in a parent POM counts),")
# OPS-REL-003
ac('OPS-REL-003',4,"The document contains, in a separate \"Post-v1 items\" section outside the 54 status lines, a row for the low-end Android tuning of D-347, and records the date, command, commit and test count of the full-suite run of `./gradlew build` (I-019); no requirement is `verified` on the strength of a `manual-pending` line (I-020). [D-347; I-019; I-020]")
# FR-REL-001 implementation note
rat('FR-REL-001',"Rejected alternative: a `buildscript { classpath }`","Measured by prototype (2026-10-20, feasibility run): applying the CycloneDX plugin by id from a subproject failed while applying it by class (`org.cyclonedx.gradle.CyclonedxPlugin`) from the script plugin wrote nine SBOMs including the TinyHttpd components. Rejected alternative: a `buildscript { classpath }`")
import json
json.dump(list(out.values()),open('fixes4.json','w',encoding='utf-8'),ensure_ascii=False,indent=1)
print(sorted(out))
