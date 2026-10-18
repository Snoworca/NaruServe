# NaruServe

## 목표

`object.md`(Android 5.0 호환 경량 REST 프레임워크 상세 설계사양서)의 목표를 달성한다.
모든 설계·구현 판단은 `object.md`를 기준으로 하며, 구현 순서와 완료 기준은 §19, 인수 테스트는 §17(T01–T50)을 따른다.

## 핵심 제약 (object.md §1.2, §5, §13 요약)

- Java 8 source/target bytecode. 최소 런타임 Android 5.0(API 21), 일반 JVM은 Java 8+.
- Core 공개 API 금지: `java.nio.file.*`, `CompletableFuture`, `java.net.http.*`, `android.*`, `javax/jakarta.servlet.*`, Kotlin stdlib 강제.
- Core 사용: `java.io.File`/Stream, `java.util.concurrent`, Java 8 `@FunctionalInterface`(Kotlin SAM 람다 지원).
- JSON: Gson 2.14.0 고정(2.15+는 API 24+). `JsonCodec` SPI 뒤에 숨기고 Core에 Gson 타입 노출 금지.
- HTTP 엔진: NanoHTTPD 포크 TinyHttpd(`/Users/beom/work/git/TinyHttpd`)를 `HttpAdapter`로 사용. Servlet은 별도 선택 모듈.
- `android.*`는 Android Adapter에만, Servlet API는 Servlet Adapter에만.

## 빌드: Gradle

- **Gradle 8.14.3 wrapper**만 사용한다(`./gradlew build`). Maven 등 다른 빌드 도구는 쓰지 않는다.
- 이 버전을 고른 이유: TinyHttpd와 같은 버전이라 `includeBuild`/버전 정렬이 쉽고, 로컬 JDK 17에서 돌고, Kotlin Gradle Plugin 2.x와 AGP 8.x(Android 샘플 앱)를 지원한다.
- 모듈: `NaruServe-api`(Core), `NaruServe`(`RestApp.create()` bootstrap), `NaruServe-tinyhttpd`, `NaruServe-gson`, `NaruServe-kotlin`, `NaruServe-logcat`, `NaruServe-filelog` — 이 일곱은 API 21 검사 대상(animal-sniffer). `NaruServe-servlet`(JVM 전용, `javax.servlet-api:3.1.0` compileOnly), `NaruServe-slf4j`(JVM 전용). 루트 프로젝트 이름은 `NaruServe-build`이고 TinyHttpd 커밋 `46b973e`을 `includeBuild`한다. 루트 `build.gradle`이 모든 모듈을 설정하고 모듈별 build 파일은 두지 않는다.
- 컴파일은 `options.release = 8`, Kotlin은 `jvmTarget = 1.8`. API 21 검사는 TinyHttpd처럼 animal-sniffer(`android-api-level-21` signature)로 한다.

## API 문서

- 공개 API 계약 문서는 `docs/api/`에 둔다. Express 사용자가 바로 적응하는 것이 목표다. 각 API에 Express 대응과 Java·Kotlin 예제를 함께 쓴다.
- 사양서에 이름이 없는 것은 지어내지 말고 **(미확정)** 으로 표시한다. API를 바꾸면 `docs/api/`도 함께 고친다.
- **(미확정)** 항목은 사용자에게 묻지 않고 구현하면서 정한다. 사양서와 어긋나지 않고 Express 사용자에게 가장 자연스러운 쪽을 고르고, 정한 뒤 `docs/api/`의 표시를 지워 확정 내용으로 고친다.

## Kotlin 호환 (object.md §12)

이 라이브러리는 Servlet 같은 HTTP API 라이브러리이고, Java로 작성하되 Kotlin에서 자연스럽게 동작해야 한다.

- 모든 콜백은 `@FunctionalInterface`로 만들어 `app.get("/x") { req, res -> }`가 DSL 없이 컴파일되게 한다.
- 공개 API의 반환·인자에 `@Nullable`/`@NonNull`을 붙여 Kotlin에서 platform type(`T!`)이 남지 않게 한다. 어노테이션은 런타임 의존성이 없는 것(compileOnly)을 쓴다.
- `req`/`res`/`app`은 Express식 짧은 메서드 이름(`req.param("id")`, `req.path()`, `res.status(201)`)을 쓴다. `Cookie` 같은 값 객체는 Servlet처럼 `getX()`/`setX()`/`isX()` 규칙을 지켜 Kotlin 프로퍼티 문법(`cookie.maxAge = 60`)이 되게 한다.
- 메서드 이름에 Kotlin 키워드(`in`, `is`, `object`, `fun`, `when`, `val`, `var`)를 쓰지 않는다. 백틱이 필요해진다.
- Core는 Kotlin stdlib에 의존하지 않는다. `reified` 확장 등 Kotlin 전용 편의 기능은 `NaruServe-kotlin` 선택 모듈에만 둔다.
- 공개 API 변경 시 Kotlin 소스로 된 컴파일·실행 테스트를 함께 유지한다(T11).

# SpecKiwi SRS workflow v1.9

This repository uses `docs/spec/` as the required source of truth for requirements.

Before making any code, test, CLI, MCP, or documentation change, agents MUST:
1. Read `docs/spec/00.index.md`.
2. Find the relevant Requirement ID in the scope SRS files.
3. Mention the Requirement ID in the work summary.
4. If no matching requirement exists, stop and ask whether to create/update an SRS requirement first.

Requirement metadata has two separate lifecycle fields:
- `Status` tracks implementation and verification progress.
- `Stability` tracks requirement maturity and change-control maturity.

Agents MUST stop before implementing a non-discarded requirement with `Stability=draft` or `Stability=deprecated` unless the user explicitly overrides that workflow.

TDD principle:
- Agents MUST follow TDD for behavior changes: write or update a failing automated test for the relevant Requirement ID before implementation, make the smallest change to pass, then refactor while keeping tests green.
- If no meaningful automated test can be written, agents MUST stop before implementation and explain the exception and alternative verification evidence.

Work-mode and the TDD First (tdd) workflow:
1. Before starting work, read the persisted work-mode with the MCP `get_work_mode` tool, or CLI `speckiwi mode` when MCP is unavailable (stored in `docs/spec/steps/state.md`). When no mode is set the mode is wait and the sdd (SRS-first) rules in this document apply.
2. Switch modes with the MCP `set_work_mode` tool (mode plus an optional activeTask for vibe/tdd) or CLI `speckiwi mode <value>`. Any mode may switch to any other of sdd, vibe, wait, and tdd; switching to sdd or wait drops a stale Active Task line, and an out-of-enum value is rejected with INVALID_MODE.
3. When the mode is `tdd`, step-scoped work follows the TDD First cycle: author the step SDS at `docs/spec/steps/<task>/design.md` per the installed SDS-MD Authoring Rules (`docs/rule/SDS-MD-Rules-v2.6.0.md`) with EARS acceptance contracts (SDS-AC), translate the SDS-ACs into failing tests and confirm they fail, implement the smallest change to green, run regression, then synthesize the step SRS and promote the step requirement with verification evidence.
4. tdd gates (all mandatory): do not write tests before the step's SDS exists, unless the skip is recorded in `intent.md` under `## SDS Skip` with a `Decision` of `skipped`, a non-empty `Reason` and an EARS `SDS-AC-n: WHEN … SHALL …` line — `SDS-E054` refuses an unrecorded skip; commit tests first and never weaken a test to reach green; never promote a step requirement without verification evidence.
5. In tdd mode the rule "do not implement behavior not covered by an SRS requirement" is satisfied for step-scoped work by the agreed SDS plus the mandatory post-hoc promotion; body-scope work keeps the sdd rules in this document.
6. Edits to existing body requirements and large architecture changes stay in sdd mode — never route them through a tdd step.

Scope SRS document naming:
1. A scope SRS document is named `docs/spec/{NN}.{scope-slug}.srs.md`, where `{NN}` is a two-digit ordering number. The full rules are in `docs/rule/SRS-MD-Rules-v2.5.0.md` §5.2.
2. Allocate `{NN}` as one above the highest number already present among the project's scope documents. The first scope document of a project is `01`, the next `02`. Do not number by tens.
3. Never reuse a number another scope document holds, and never renumber an existing document.
4. Prefer `speckiwi scaffold-scope <Name>:<PREFIX> --apply`, which allocates the number and registers the document in both index sections in one operation, over writing the file and the index rows by hand.

Agents MUST NOT:
- Implement behavior that is not covered by an SRS requirement.
- Create an alternate requirements source outside `docs/spec/`.
- Change requirement IDs manually.
- Mark requirements as verified without evidence.
- Introduce or invoke bulk-archive / bulk-finalize tooling that flips multiple requirements to `verified` or empties Active Target without per-requirement evidence and stability gate checks.

When SpecKiwi MCP tools are available, agents MUST use them for requirement lookup and safe SRS updates. Normal target-scoped SRS mutations go through MCP only: the `speckiwi` CLI diagnoses installation, version and configuration and guides MCP recovery, and is not a normal replacement for an MCP mutation. Registering an unregistered target is the single exception, and the target workflow below names it. CLI reads remain available for lookup while MCP is being repaired.

Per-call workspace root:
1. The MCP server resolves its root from its own process working directory, and SRS is written only there.
2. The `workflow_*` family accepts an optional absolute `workspaceRoot` on every tool, and the `orchestrate_*` family accepts it on every tool except `orchestrate_replay_apply` and `orchestrate_preflight`.
3. The SRS query tools also accept it: `list_requirements`, `search_requirements`, `get_requirement`, `validate_spec`, `summarize_target`, `get_active_target`, `list_completed_work`, `validate_step`, `get_work_mode`, `check_vibe_gate`, `list_dirty_edges`, `list_compat_edges`, `list_steps`, `check_sds` and `check_test_sufficiency` — each reading the named checkout and writing nothing. They are refused when that checkout holds no `docs/spec/00.index.md`, and `check_test_sufficiency` refuses there an `sds` path under `docs/spec` (a step `design.md`), so that check runs through `speckiwi coverage --tests` inside the worktree.
4. Every tool that writes under `docs/spec` or allocates a Requirement ID refuses `workspaceRoot` fail-closed, and so do `mcp_workspace_info` and `get_next_work_order`. Refusal is the default, so a tool not named here refuses it.
5. An accepted `workspaceRoot` MUST be an absolute path to an existing git top level that is a worktree of the startup root's repository; a path argument landing under `docs/spec` is refused even on a tool that accepts the root, unless the tool declares that it takes no caller-supplied path — which is what lets an SRS query filter on a `docs/spec` reference.
6. Agents MUST confirm workspace identity from the `mcpWorkspace` envelope — `workspaceRoot` plus `rootSource` — before any target-scoped read or mutation. `rootSource` reads `per-call-workspace-root` exactly when a supplied `workspaceRoot` passed every gate, and `server-cwd-discovery` or `auto-init` otherwise.

Current work status workflow:
1. Read the active target with MCP `get_active_target`, or CLI `speckiwi active-target --json` if MCP is unavailable.
2. If `activeTarget` is empty, report that no active target is set and ask which target to use before making target-scoped changes.
3. Read `summary.countsByStatus`, `summary.countsByStability`, `summary.stabilityBlockers`, `summary.stabilityWarnings`, and `summary.newWorkCandidates` before selecting work.
4. Read open work with MCP `list_requirements` for `status=in_progress`, `status=blocked`, and `status=implemented`; CLI fallback is `speckiwi list --status <status> --json`.
5. Check missing verification evidence through `summary` or MCP `summarize_target` before saying work is complete.
6. Read recent completed work with MCP `list_completed_work`; CLI fallback is `speckiwi completed-work --json`.

Next target authoring workflow:
1. If the user asks to set the next target, first read the current Active Target and Target Map.
2. If the target is not registered, use a supported target-registration mutation such as MCP `set_active_target` with creation support, or CLI `speckiwi set-active-target <target> --create` when that option is available.
3. If the configured MCP/CLI cannot register the target, stop before target-scoped SRS changes and report the tool gap, unless the user explicitly authorizes a minimal SRS-MD patch.
4. After target assignment, confirm the resolved Active Target with MCP `get_active_target`, or CLI `speckiwi active-target --json` if MCP is unavailable.
5. When the user provides a target goal, record it with MCP `set_target_goal`. Recording a goal is a mutation, so it waits for MCP rather than moving to the command line; if MCP cannot be repaired, report the gap instead.
6. For later SRS creation, omit the target only when the tool supports Active Target defaulting; otherwise pass the confirmed Active Target explicitly.
7. If the user provides an explicit different target for a requirement, the explicit target wins over Active Target.

Merge-time duplicate Requirement ID repair workflow:
1. Run `speckiwi validate --json` or MCP `validate_spec` first. Use repair only when `SRS-E002` duplicate Requirement ID diagnostics exist, or when a named duplicate ID is confirmed in parsed diagnostics.
2. Resolve normal Git conflict markers before repair. Then run MCP `diagnose_requirement_id_collisions` or CLI `speckiwi repair requirement-id-collisions diagnose --json`.
3. Select explicit keep and rename occurrences by `filePath`, `headingLine`, and `blockHash`. A duplicate ID alone is never enough to write.
4. Create a dry-run plan with MCP `plan_requirement_id_collision_repair` or CLI `speckiwi repair requirement-id-collisions plan --duplicate-id <id> --keep <file:line:blockHash> --rename <file:line:blockHash> [--replacement-id <id>|--allocate-next] --write-plan <path> --json`.
5. Apply only from the explicit plan or equivalent explicit mapping with MCP `apply_requirement_id_collision_repair` or CLI `speckiwi repair requirement-id-collisions apply --plan <path> --json`. `--ignore-lock` is allowed only on apply and bypasses only the SRS mutation lock.
6. Do not use collision repair for general renumbering, gap filling, ID beautification, bulk archive, bulk finalize, or Status/Stability changes. When two duplicate logical requirements should be merged or discarded, first repair IDs to uniqueness, then use separate guarded SRS mutations for discard, supersedes, Status, Stability, AC, or evidence changes.
7. When implemented runtime CLI or MCP repair tooling is available, do not hand-edit Requirement IDs. If tooling is unavailable and the user explicitly authorizes a degraded SRS-MD patch, limit it to the selected occurrence and explicitly mapped references.
8. Finish with `speckiwi validate --fail-on-warning --json`, `speckiwi summary --target <target> --json`, and `speckiwi links check --json` or MCP equivalents. Evidence must show duplicate IDs are zero and ambiguous references were reported or explicitly mapped.

The Completed Work Log — inline in `docs/spec/00.index.md` §7 and its split history file `docs/spec/91.completed-work-log.md` — is a read-only summary for agents. Requirement Block status, Acceptance Criteria, Verification Evidence, and Change Notes remain the source of truth for completion.

<!-- /SpecKiwi SRS workflow -->
