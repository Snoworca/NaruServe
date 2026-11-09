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
- 모듈: `NaruServe-api`(Core), `NaruServe`(`RestApp.create()` bootstrap), `NaruServe-tinyhttpd`, `NaruServe-gson`, `NaruServe-kotlin`, `NaruServe-logcat`, `NaruServe-filelog` — 이 일곱은 API 21 검사 대상(animal-sniffer). `NaruServe-servlet`(JVM 전용, `javax.servlet-api:3.1.0` compileOnly), `NaruServe-slf4j`(JVM 전용), `NaruServe-android`(`NaruServe` + Logcat 로거 + INTERNET 권한의 Android 라이브러리 AAR, Android Gradle Plugin 8.13.2로 Android SDK가 있을 때만 빌드). `NaruServe`는 API + TinyHttpd + Gson(`api`) + 콘솔 로거이고 `NaruServe-kotlin`은 `NaruServe`를 포함한다. 루트 프로젝트 이름은 `NaruServe-build`이고 TinyHttpd 체크아웃을 `includeBuild`하며 커밋 `e73cd5d`(TinyHttpd 3.1.1: RAW 요청 대상 정책, Gradle 좌표 `com.snoworca:tinyhttpd-*`)에서 검증했다(HEAD가 다르면 `settings.gradle`이 경고만 낸다). 루트 `build.gradle`이 모든 모듈을 설정하고 모듈별 build 파일은 두지 않는다.
- 루트 `build.gradle` 밖의 빌드 설정은 릴리스 전용 `gradle/release.gradle`(CycloneDX SBOM, 취약점 스캔, 라이선스 보고) 하나뿐이며, 루트 `build.gradle`이 `-Prelease`일 때만 `apply from:`으로 적용하므로 기본 `./gradlew build`는 릴리스 플러그인을 해석하지 않는다.
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

# SpecKiwi SRS workflow v1.11
Written by speckiwi 5.3.7.

This repository uses `docs/spec/` as the required source of truth for requirements.

SpecKiwi tool check (once per session, before the first SpecKiwi call):
1. Run `speckiwi --version`. If the command is not found, check `node --version`: SpecKiwi needs Node.js 22 or newer, so below 22 stop and report rather than changing the Node installation. Otherwise install the CLI with `npm install -g speckiwi@latest` and run `speckiwi --version` again; if the global install is refused, report it instead of retrying with elevated rights.
2. Compare the installed version with the `Written by` version above.
   - If the CLI is older, read `npm view speckiwi version` and run `npm install -g speckiwi@latest` only when that version is newer than the installed CLI; never install an older version over it. If npm has nothing newer, keep the installed CLI, report the difference, and run neither `speckiwi init` nor `speckiwi upgrade` with it: an older CLI rewrites this block, the rules documents and the agent setup back to its own version.
   - If the CLI is newer, this block, the rules documents and the skills are out of date: tell the user and propose `speckiwi upgrade`, show `speckiwi upgrade --dry-run` first, and upgrade only when the user agrees. It never edits requirements, but it rewrites AGENTS.md, CLAUDE.md and other tool-owned files and may remove the project's own skill copies and its `.mcp.json` speckiwi key. Those removals are changes to commit, after which each collaborator needs `speckiwi init` on their machine; `speckiwi upgrade --project-install` keeps the project copies instead.
3. If no directory from the working directory up to the git top level holds `docs/spec/00.index.md` and none exists elsewhere in the repository, the project has no SRS yet: propose `speckiwi init` at the git top level and run it only when the user agrees, showing `speckiwi init --dry-run` first, because init creates the SRS there, rewrites AGENTS.md and CLAUDE.md, writes git and agent hooks, installs the global skills and registers the MCP server at user scope. If no SpecKiwi MCP tools are listed in the session, propose the same `speckiwi init` — it registers the MCP server at user scope for Claude Code and Codex — run it only when the user agrees, then ask the user to restart the agent session; if init reports that registration as already present (`skipped`) while the SpecKiwi MCP tools are still missing, stop and report instead of running init again.
4. Every SpecKiwi MCP reply carries `mcpWorkspace.packageVersion`. If it differs from `speckiwi --version`, ask the user to restart the session once; if they still differ, report both versions before relying on behavior this block describes.

Before making any code, test, or documentation change, agents MUST:
1. Read `docs/spec/00.index.md`.
2. Find the relevant Requirement ID in the scope SRS files (MCP `search_requirements`, CLI `speckiwi search`). Search in both languages: put the words you would search for and their equivalents in the other language into the same query string (add English words to a Korean request; add Korean words to an English request or to a requirement you are writing in English).
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
2. Switch modes with the MCP `set_work_mode` tool (mode plus an optional activeTask for vibe/tdd) or CLI `speckiwi mode <value>`. Any mode may switch to any other of sdd, vibe, wait, and tdd; a switch drops the Active Task line unless it moves to vibe or tdd naming a task, and an out-of-enum value is rejected with INVALID_MODE.
3. When the mode is `tdd`, step-scoped work follows the TDD First cycle: record EARS acceptance lines (`AC-<n>: WHEN … THE SYSTEM SHALL …`) in `docs/spec/steps/<task>/intent.md` under `## Acceptance` and check them with `speckiwi step validate`, translate them into failing tests and confirm they fail, implement the smallest change to green, run regression, then synthesize the step SRS and promote the step requirement with verification evidence. A step SDS (`design.md` per the installed SDS-MD Authoring Rules, `docs/rule/SDS-MD-Rules-v2.6.0.md`) is written only when the user asks for one.
4. tdd gates (all mandatory): no test before the step's acceptance contract exists — `STEP_ACCEPTANCE_MISSING` refuses a step without it; commit tests first and never weaken a test to reach green; never promote a step requirement without verification evidence.
5. In tdd mode the rule "do not implement behavior not covered by an SRS requirement" is satisfied for step-scoped work by the step's acceptance lines plus the mandatory post-hoc promotion, and in vibe mode by the step SRS synthesized before the commit (item 7); body-scope work keeps the sdd rules in this document.
6. Edits to existing body requirements and large architecture changes stay in sdd mode — never route them through a tdd step.
7. When the mode is `vibe`, code may come first for the Active Task, so the TDD principle and the stop-and-ask rule above do not hold that task back; the step SRS MUST still be synthesized (MCP `synthesize_step_srs`, or CLI `speckiwi step synthesize <task>` when MCP is unavailable) before the commit that lands the task; `check_vibe_gate` (CLI `speckiwi vibe-gate check`) only confirms that the Active Task's step directory exists, not that the synthesis ran. Switch to vibe only when the user asks for it.

Scope SRS document naming:
1. A scope SRS document is named `docs/spec/{NN}.{scope-slug}.srs.md`, where `{NN}` is a two-digit ordering number. The full rules are in `docs/rule/SRS-MD-Rules-v2.6.0.md` §5.2.
2. Allocate `{NN}` as one above the highest number already present among the project's scope documents. The first scope document of a project is `01`, the next `02`. Do not number by tens.
3. Never reuse a number another scope document holds, and never renumber an existing document.
4. Prefer `speckiwi scaffold-scope <Name>:<PREFIX> --apply`, which allocates the number and registers the document in both index sections in one operation, over writing the file and the index rows by hand.

Agents MUST NOT:
- Implement behavior that is not covered by an SRS requirement (the tdd and vibe items above say how step-scoped work is covered).
- Create an alternate requirements source outside `docs/spec/`.
- Change requirement IDs manually.
- Mark requirements as verified without evidence.
- Flip several requirements to `verified`, or empty the Active Target, in one operation without per-requirement evidence and stability gate checks.

When SpecKiwi MCP tools are available, agents MUST use them for requirement lookup and safe SRS updates. Normal target-scoped SRS mutations go through MCP only: the `speckiwi` CLI diagnoses installation, version and configuration (see the tool check above) and is not a normal replacement for an MCP mutation. Registering an unregistered target is the single exception among target-scoped SRS mutations, and the target workflow below names it; the CLI forms this block gives for work-mode, step and repair commands are for when MCP is unavailable. CLI reads remain available for lookup while MCP is being repaired.

Per-call workspace root:
1. The MCP server binds once, at startup, to the nearest directory at or above its working directory that holds `docs/spec/00.index.md` — never the home directory — and writes SRS only there.
2. The `workflow_*` family accepts an optional absolute `workspaceRoot` on every tool, and the `orchestrate_*` family on every tool except `orchestrate_replay_apply` and `orchestrate_preflight`.
3. The SRS query tools also accept it: `list_requirements`, `search_requirements`, `get_requirement`, `validate_spec`, `summarize_target`, `get_active_target`, `list_targets`, `list_roadmap_items`, `get_roadmap_item`, `check_roadmap`, `list_completed_work`, `validate_step`, `get_work_mode`, `check_vibe_gate`, `list_dirty_edges`, `list_compat_edges`, `list_steps`, `check_sds` and `check_test_sufficiency` — each reading the named checkout and writing nothing.
4. Every other tool refuses `workspaceRoot`, including every tool that writes under `docs/spec` or allocates a Requirement ID; run those from a session started in that checkout.
5. An accepted `workspaceRoot` MUST be the absolute git top level of a worktree of the same repository; a refused call names its reason.
6. Agents MUST confirm workspace identity from the `mcpWorkspace` envelope — `workspaceRoot` plus `rootSource` — before any target-scoped read or mutation. `rootSource` reads `per-call-workspace-root` exactly when a supplied `workspaceRoot` passed every gate, `server-cwd-discovery` when the server found the SRS from its working directory, and `unbound` when it found none. An unbound server answers every call with `MCP_WORKSPACE_UNBOUND` and creates nothing: start the agent session (or set the MCP server's working directory) in the project directory, propose `speckiwi init` there first if the project has no SRS yet (tool check item 3), then restart the session.

Current work status workflow:
1. Read the active target with MCP `get_active_target`, or CLI `speckiwi active-target --json` if MCP is unavailable.
2. If `activeTarget` is empty, report that no active target is set and ask which target to use before making target-scoped changes.
3. Read `summary.countsByStatus`, `summary.countsByStability`, `summary.stabilityBlockers`, `summary.stabilityWarnings`, and `summary.newWorkCandidates` before selecting work.
4. Read open work with MCP `list_requirements` for `status=in_progress`, `status=blocked`, and `status=implemented`; CLI fallback is `speckiwi list --status <status> --json`.
5. Check missing verification evidence through `summary` or MCP `summarize_target` before saying work is complete.
6. Read recent completed work with MCP `list_completed_work`; CLI fallback is `speckiwi completed-work --json`.

Next target authoring workflow:
1. If the user asks to set the next target, first read the current Active Target and Target Map.
2. If the target is not registered, register it with MCP `set_active_target` (`create: true`) or CLI `speckiwi set-active-target <target> --create`.
3. If the configured MCP/CLI cannot register the target, stop before target-scoped SRS changes and report the tool gap, unless the user explicitly authorizes a minimal SRS-MD patch.
4. After target assignment, confirm the resolved Active Target with MCP `get_active_target`, or CLI `speckiwi active-target --json` if MCP is unavailable.
5. When the user provides a target goal, record it with MCP `set_target_goal`. Recording a goal is a mutation, so it waits for MCP rather than moving to the command line; if MCP cannot be repaired, report the gap instead.
6. `add_requirement` files a new requirement under the Active Target when `target` is omitted; pass the target explicitly when in doubt.
7. If the user provides an explicit different target for a requirement, the explicit target wins over Active Target.

Merge-time duplicate Requirement ID repair workflow:
1. Run `speckiwi validate --json` or MCP `validate_spec` first. Use repair only when `SRS-E002` duplicate Requirement ID diagnostics exist, or when a named duplicate ID is confirmed in parsed diagnostics.
2. Resolve normal Git conflict markers before repair. Then run MCP `diagnose_requirement_id_collisions` or CLI `speckiwi repair requirement-id-collisions diagnose --json`.
3. Select explicit keep and rename occurrences by `filePath`, `headingLine`, and `blockHash`. A duplicate ID alone is never enough to write.
4. Create a dry-run plan with MCP `plan_requirement_id_collision_repair` or CLI `speckiwi repair requirement-id-collisions plan --duplicate-id <id> --keep <file:line:blockHash> --rename <file:line:blockHash> [--replacement-id <id>|--allocate-next] --write-plan <path> --json`.
5. Apply only from the explicit plan or equivalent explicit mapping with MCP `apply_requirement_id_collision_repair` or CLI `speckiwi repair requirement-id-collisions apply --plan <path> --json`. `--ignore-lock` is allowed only on apply and bypasses only the SRS mutation lock.
6. Do not use collision repair for general renumbering, gap filling, ID beautification, bulk archive, bulk finalize, or Status/Stability changes. When two duplicate logical requirements should be merged or discarded, first repair IDs to uniqueness, then use separate guarded SRS mutations for discard, supersedes, Status, Stability, AC, or evidence changes.
7. Do not hand-edit Requirement IDs. If the user explicitly authorizes a degraded SRS-MD patch, limit it to the selected occurrence and explicitly mapped references.
8. Finish with `speckiwi validate --fail-on-warning --json`, `speckiwi summary --target <target> --json` (MCP `summarize_target`), and `speckiwi links check --json` (CLI only). Evidence must show duplicate IDs are zero and ambiguous references were reported or explicitly mapped.

The Completed Work Log — inline in `docs/spec/00.index.md` §7 and its split history file `docs/spec/91.completed-work-log.md` — is a read-only summary for agents; add rows only with MCP `add_completed_work`, never by hand. Requirement Block status, Acceptance Criteria, Verification Evidence, and Change Notes remain the source of truth for completion.

<!-- /SpecKiwi SRS workflow -->
