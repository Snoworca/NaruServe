# 챗봇 OpenAI 호환 API 후속 연구: node-api 영향 · 자동 기억 · 보관 기간

- 작성일: 2026-10-26
- 앞선 문서: [2026-10-24.chatbot-session-memory-and-trigger.md](2026-10-24.chatbot-session-memory-and-trigger.md) §7 (세션 · OpenAI 호환 API · 멀티모달 결정)
- 질문 셋 (2026-10-26 사용자 지시)
  1. 멀티모달 입출력 방식은 수용한다. 다만 node-api SPI 에 미치는 영향을 확인한다.
  2. 세션 안의 에이전트가 메모리 노드 없이 기억하는 안(자동 기억)과 세션 단위 값(`$session.*`)을 연구한다.
  3. 대화 기록의 기본 보관 기간과 상한을 연구한다.
- 방법: 독립 연구 서브에이전트 셋이 질문 하나씩을 맡았고, 세 결과가 겹치거나 어긋나는 곳을 §4 에서 맞췄다.
- 기준: 코드 사실은 `origin/develop`(`a8aeae1ded`) 기준이며 줄 번호도 그 기준이다. 메인 트리 체크아웃은 `origin/develop` 보다 크게 뒤처져 있다.
- 표기: **사실**은 코드나 공식 문서로 확인한 것, **제안**은 연구가 권하는 것, **미확인**은 확인하지 못한 것이다.

## 0. 결론

1. **멀티모달 입력에는 node-api 변경이 필요 없다.** 바이너리를 담는 모델(`DataRecord.binary` · `BinaryData` · `BinaryService`)이 이미 있고, 서버가 그것을 blob 저장소에 연결해 둔다.
2. **멀티모달 출력에는 구멍이 있다. 하지만 node-api 가 아니라 엔진과 서버의 문제다.** 실행 결과에 바이너리가 실리지 않고, 응답 노드의 바이너리 응답은 아직 구현되지 않았으며, 서명된 내려받기 링크 기능이 없다.
3. **node-api 에 더할 것은 대화 접근자 하나다.** 기존 코드가 깨지지 않는 default 메서드로 더하므로 하위 호환 위험이 작다. 저장소 SPI 자체는 기존 선례(dedupe)처럼 엔진에 두기를 권한다. 이것은 「node-api 에 SPI 를 연다」는 앞선 설계와 다르므로 결정이 필요하다(§5-1).
4. **자동 기억은 채택을 권한다. 다만 기록은 에이전트가 아니라 서버의 챗 API 처리부가 턴마다 한 번 쓴다.** 에이전트는 기록을 자동으로 읽기만 한다. 에이전트가 직접 쓰게 하면 에이전트가 여럿일 때, 재시도할 때, 같은 세션에 요청이 동시에 올 때 기록이 섞이거나 두 번 저장된다.
5. **세션 값(`$session.*`)도 채택을 권한다.** 기존 정적 데이터 구조(`RunStaticData`)를 그대로 따르면 된다.
6. **보관 기간은 「마지막 사용 뒤 30일」, 상한을 넘으면 오래된 턴부터 지우기를 권한다.** 정리 작업은 실행 기록 정리기와 같은 형태로 만들고, 설치 직후부터 모든 DB 에서 켠다.

## 1. 멀티모달 입출력이 node-api 에 미치는 영향

### 1.1 영향 표

| 항목 | 지금 상태 (사실) | 필요한 변경 | 하위 호환 위험 |
|---|---|---|---|
| 아이템의 바이너리 모델 | `DataRecord.binary: Map<String, BinaryData>` (`node-api/.../model/DataRecord.java:13`). 저장 종류는 6가지다(`BinaryStorageKind.java`) | 없음 | 없음 |
| 노드가 바이너리를 쓰는 표면 | `NodeExecution.binary()` (`NodeExecution.java:304`). 엔진이 `BlobBackedBinaryService` 를 주입하고(`NodeExecutorAdapter.java:2412-2413`), 저장하면 MANAGED `blob://<sha>` 가 된다(`BlobBackedBinaryService.java:176-188`) | 없음 | 없음 |
| 서버 blob 저장소 | `BlobStores.forServer` 가 파일 또는 DB 저장소를 고르고(`ServerBootstrap.java:1939`), 엔진에 주입한다(`:2146`). 임베드 모드는 `storageRoot/blob` 파일 저장소를 쓰고, 그것도 없으면 메모리 대체값을 쓴다(`EngineConfig.java:2745-2752`, `NodeExecutionImpl.java:1086`) | 없음 | 없음 |
| 트리거 첨부 → 아이템 | 엔진의 `seedRecord` 가 `BinaryAttachment` 값을 바이너리 채널로 옮긴다(`WorkflowRunnerImpl.java:17992-18008`). 웹훅 멀티파트는 INLINE base64 로(`WebhookTriggerAdapter.java:2647`), 폼은 MANAGED 로 `$json` 에 넣는다(`SubmittedForm.java:142-153`). 두 경로의 방식이 서로 다르다 | 서버 · 엔진 추가. 챗 API 처리부가 첨부를 blob 에 먼저 저장한 뒤 MANAGED `BinaryAttachment` 를 만든다. 생성자가 public 이다(`BinaryAttachment.java:41`) | 없음 |
| 출력 바이너리 찾기 | 실행 결과와 포트 출력이 `record.getData()` 만 직렬화한다(일반 실행 `WorkflowRunnerImpl.java:14738-14742`, 재개 복원 `:13354`, 포트 출력 `:15432`). 응답 노드의 `binary` 모드는 `binaryInputField` 메모만 남기고(`RespondToWebhookExecutor.java:119-122`, `:273-279`), 서버와 엔진에서 그 값을 읽는 코드가 없다(검색 0건) | 엔진 추가. 실행 결과에 바이너리 참조를 더한다 | 엔진 공개 타입 `WorkflowRunResult` 에 추가형으로만 더하면 낮다 |
| 서명된 내려받기 링크 | 없다(검색 0건). `/blob` GET 은 `WORKFLOW_READ` 권한을 요구한다(`ServerBootstrap.java:3004-3008`). HMAC 키를 파생하는 선례는 `TeamImpactTokenKey` 에 있다 | 서버 추가 | 없음 |
| 세션 삭제 시 첨부 삭제 | `StreamingBlobStore` 에는 삭제와 참조 계수가 없다. 내용 주소 방식이라 같은 파일을 여러 곳이 공유할 수 있다 | 서버 추가(참조 계수 또는 세션 소유 표시) | 없음 |
| 대화 기록 접근 | node-api 에 없다. 메모리 노드는 static 맵을 쓴다(`MemoryBufferNode.java:60`, `:82`). node-api 는 AKX 에 의존하지 않는다(`node-api/build.gradle:66-89`) | node-api 추가(접근자와 중립 값 타입) | default 메서드면 낮다. ai-nodes 의 테스트 스텁 다수(`*ExecutionStub`)가 `NodeExecution` 을 직접 구현하므로 abstract 메서드로 더하면 컴파일이 깨진다 |
| 공개 표면 검사 | `verifyPublicApiSurface`(`node-api/build.gradle:635`)가 `node-api/docs/implementation/public-api-surface.txt` 의 타입 221개와 소스를 대조한다. `check` → `verifyPublicApiDocs`(`:848`) → `verifyPublicApiSurface` 순서로 이어진다 | 새 타입을 목록에 올린다 | 빠뜨리면 빌드가 실패한다 |
| 빌트인 노드 census | node-api 에 타입을 더하는 것만으로는 영향이 없다. 새 노드를 만들면 노드 목록(`orchestrationgrid-builtin-nodes/profiles/all.tsv`, `build.gradle:62`, `:144`)과 census 를 함께 맞춘다 | 노드를 더할 때만 | 없음 |

### 1.2 SPI 배치 (제안)

선례는 dedupe 다(사실). 노드 쪽 접근자 `DedupeAccessor` 는 node-api 에 있고, 호스트 쪽 저장소 `DedupeStore` 는 엔진에 있다. 엔진은 `EngineConfig.Builder.dedupeStore`(`EngineConfig.java:2110`)로 구현을 받고, 서버 구현은 `JdbcDedupeStore`(`server/store/repository/JdbcDedupeStore.java:82`)다.

| 계층 | 두는 것 |
|---|---|
| node-api | `execution.ConversationAccessor` 와 진입점 `NodeExecution.conversation()`(default 메서드). 호스트가 주입하지 않았으면 `DedupeAccessor.empty()` 처럼 명확한 오류로 거부한다. 불변 값 타입 `model.ConversationMessage`(역할 · 부분들 · 시각 · metadata). 부분은 텍스트이거나 기존 `BinaryData` 참조다 |
| 엔진 | `ConversationStore` 와 `InMemoryConversationStore`, `EngineConfig.Builder.conversationStore(..)`. 임베드 모드의 기본값은 메모리 구현이다 |
| 서버 | `JdbcConversationStore` 와 세션 레코드 저장소. 제품별 SQL 은 `ent/` 에 둔다. 스키마를 더하므로 `SCHEMA_VERSION` 을 올린다. 세션 생성 · 삭제 API 는 서버에만 있다 |
| ai-nodes | 메모리 노드가 접근자를 AKX `ChatMessageStore` 로 감싸 static 맵을 대체한다. AKX `ChatMessage` 는 텍스트 내용과 문자열 metadata 만 가지므로 첨부 참조는 metadata 에 담는다(javap 로 확인) |

- 바이너리 SPI 와 대화 SPI 는 합치지 않는다. 대화 메시지는 기존 `BinaryData` 참조만 담는다.
- 워크플로 범위는 노드가 아니라 호스트가 `ExecuteIdentity.getWorkflowId()` 로 강제한다. 노드가 다른 워크플로의 세션을 열 수 없다.

## 2. 자동 기억 (제안 A)

### 2.1 지금 상태 (사실)

- 에이전트는 `@OgAiPort(type = MEMORY)` 로 메모리를 받는다(`AiAgentNode.java:73`). 연결이 없으면 `null` 을 받아 기억 없이 실행한다(`:113`, `:636-638`).
- 대화 기록은 대화 메시지로 들어가지 않는다. 기록을 텍스트로 만들어 `ContextContributor` 로 넣는다(`:115-117`, `:647-660`).
- 에이전트는 실행에 성공한 뒤 사용자 메시지와 응답 메시지를 따로 두 번 쓴다(`:137-138`). AKX 에는 여러 건을 한 번에 쓰는 `appendAll` 과 기록 판 번호(`revision`)가 있지만 쓰지 않는다. 판 번호를 비교해 덧붙이는 기능은 없다.
- 에이전트를 도구로 쓰는 노드(`agent-tool`)에는 MEMORY 연결이 없다(`AgentToolNode.java:52` 부근).
- 실행 출처에는 `SUB_WORKFLOW` · `RETRY` · `TOOL_CALL` 이 있다(`RunOrigin.java:65`, `:75`, `:96`). 자식 워크플로 실행은 부모의 실행 형태, 호출자 신원, 재시도 출처(`childRetriedOrigin`), `traceId` · `correlationId`, 판단 주체를 물려받는다(`WorkflowRunnerImpl.java:20261-20305`). 세션은 지금 물려받는 값에 없다.
- n8n 은 메모리가 선택 입력이며 연결이 없으면 기억하지 않는다(`ToolsAgent/common.ts:512-517`, `ChatTrigger.node.ts:149`). 자동 기억은 n8n 에 없는 사용 방식이다.

### 2.2 의미 정의 (제안)

- 세션이 실린 실행에서 MEMORY 가 연결되지 않은 에이전트는 그 세션의 지난 턴을 자동으로 읽는다.
- **세션 기록은 서버의 챗 API 처리부가 쓴다.** 턴이 성공하면 사용자 입력과 최종 응답을 한 트랜잭션에서 `appendAll` 로 쓴다.
- 메모리 노드를 연결하면 그 노드가 정한 범위(창 길이, `customKey` 로 나눈 기록)가 자동 기억을 대신한다.
- 에이전트 옵션에 「대화 기록: 자동 / 사용 안 함」을 둔다. 분류만 하는 에이전트처럼 기록이 필요 없는 경우를 위해서다.

### 2.3 경우별 처리 (제안)

| 경우 | 처리 |
|---|---|
| 한 워크플로에 에이전트가 여럿 | 모두 같은 기록을 읽는다. 쓰는 것은 챗 API 처리부뿐이라 중간 에이전트의 출력이 기록에 섞이지 않는다 |
| 서브워크플로 | 세션을 물려받아 읽는다. 호출자 신원을 물려주는 경로와 같은 경로로 전달한다 |
| 도구로 호출되는 에이전트 | 기본으로 끈다. 비용이 늘고, 하위 작업에 대화 전체가 노출되기 때문이다 |
| 일시정지 뒤 재개 | 턴이 끝날 때 한 번만 쓴다. 재개된 실행이 끝나야 그 턴이 기록된다 |
| 실패 뒤 재시도 | 실패한 턴은 아무것도 쓰지 않는다. 실패 지점부터 다시 실행(`RETRY`)한 결과는 대화에 쓰지 않는다. 이미 API 응답이 끝났기 때문이다. 클라이언트가 같은 요청을 다시 보내는 경우는 턴 id 로 중복을 막는다 |
| 같은 세션에 요청 둘이 동시에 옴 | 세션 행에 진행 중인 턴을 하나만 둔다. 「비어 있으면 내 것으로」 조건부 UPDATE 로 잡고, 기한이 지나면 풀린다. 두 번째 요청은 409 로 거절한다. 모델을 호출하는 동안 DB 잠금을 쥐지 않는다 |

### 2.4 구현 영향 (제안)

| 모듈 | 바뀌는 것 |
|---|---|
| node-api | §1.2 의 대화 접근자. 세션이 없는 실행에서는 「묶인 세션 없음」을 돌려준다 |
| 엔진 | 실행 문맥에 세션을 싣고 자식 실행에 전달한다 |
| ai-nodes | 메모리가 연결되지 않았을 때 대화 접근자로 읽는다. 옵션 하나를 더한다 |
| 서버 | JDBC 대화 저장소, 턴 마감 시 기록, 진행 중인 턴 표시. 스키마 버전을 올린다 |
| 임베드 모드 | 세션이 없으면 지금과 같이 동작한다 |

## 3. 세션 값 `$session.*` (제안 B)

### 3.1 재사용할 수 있는 구조 (사실)

- `RunStaticData` 는 실행이 시작될 때 값을 고정한 사본을 표현식에 준다(`RunStaticData.java:21-26`). 바뀐 키만 되쓰며(`:28-32`), 되쓰기는 실행이 끝날 때 시도마다 한 번이고 일시정지 기록보다 먼저 한다(`WorkflowRunnerImpl.java:2330-2347`).
- 정적 데이터는 서버 DB 에도 저장된다(`JdbcStaticDataStore.java:33-37`). **앞선 문서 §1.4 의 「`staticData` 는 파일(`FileStaticDataStore`)에 저장된다」는 서술은 낡았다.**
- `core.util.ExecutionData` 노드가 실행 단위 값을 쓴다(`ExecutionDataNode.java:26`, `:43`).
- n8n 의 staticData 범위는 `global` 과 `node:<이름>` 둘뿐이다(`workflow.ts:217-232`). 세션 단위 값은 없다.
- OpenAI 대화에는 `metadata` 가 있다: 16쌍까지, 키 64자, 값 512자이며, `POST /v1/conversations/{id}` 로 갱신한다. 갱신이 병합인지 통째 교체인지는 문서에 없다(미확인).

### 3.2 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| B1. 「세션 값 저장」 노드 + 정적 데이터 접근자에 세션 범위 추가 | 기존 구조를 재사용한다. 비개발자가 노드로 쓴다. 코드 노드도 같은 접근자를 쓴다 | 같은 턴 안의 표현식은 새 값을 보지 못하고 다음 턴부터 본다 |
| B2. 대화 `metadata` 를 `$session.metadata` 로 읽기 전용 공개 | 규격에 있는 필드라 호출자가 사용자 이름 등을 실어 보낼 수 있다 | 문자열만, 16쌍까지라 워크플로의 저장소로는 부족하다 |
| B3. 값마다 버전을 비교해 쓰기 | 경합을 탐지한다 | §2.3 의 「세션당 진행 중인 턴 하나」가 있으면 경합이 생기지 않아 얻는 것이 없다 |

### 3.3 권장안: B1 + B2

- 경합: 키 단위로 마지막에 쓴 값이 이긴다.
- 저장 위치: 세션 행의 값 열. 기록 덧붙이기와 같은 트랜잭션에서 반영하므로, 실패한 턴은 기록도 값도 남기지 않는다.
- 크기 상한: 키 개수 · 값 바이트 · 전체 바이트를 서버 설정(`ServerConfig`)과 `EngineConfig` 로 둔다.
- 예약 키: `id` · `metadata` 로 쓰려 하면 거절한다.
- `$session` 루트 변수를 더하는 곳(사실):
  - 조건부 바인딩 목록 `EngineReservedNames.java:28-29`. `$execution` 이 같은 범주다
  - 주의: 같은 파일에 이미 `SESSION_ROOTS`(`:26`)라는 이름이 있다. 내용은 `process` · `$replay` · `$now` · `$today` 이고 대화 세션과 뜻이 다르다. `$session` 을 더할 때 이 이름이 헷갈리지 않게 정리한다
  - 실행 동안 바뀌지 않는 변수로 분류하는 `AstDependencyAnalyzer.java:12-13`
  - 값 바인딩 `WorkflowRunnerImpl.java:19040` 부근
  - 코드 노드 바인딩 `JavaScriptCodeBindingsFactory.java:230-232`
  - 자동 완성은 매니페스트(`ExpressionRuntimeSurfaceManifest.java:98`)에서 유도된다. 설명 문구만 `expression-catalog-messages_en.json` · `_ko.json` 에 더한다

## 4. 보관 기간과 상한

### 4.1 OG 의 기존 패턴 (사실)

- 실행 기록: `server.runRetention.*` 이 14일(336시간), 주기 60분, 쪽 크기 2,000 이다(`ServerConfig.java:298-303`). n8n `pruneDataMaxAge` 336 과 같다. 운영자가 값을 적지 않으면 외부 DB 에서는 켜지고 내장 DB 에서는 꺼진다(`ServerConfig.java:3675-3676`).
- 감사 원장: 90일이며 기본으로 꺼져 있다. 감사 기록은 증거이기 때문이다(`ServerConfig.java:308-311`).
- blob: `retentionDays` 기본 0 은 지우지 않는다는 뜻이다(`PersistenceBlobConfig.java:16-21`).
- 휴지통: 30일이 코드 상수다(`WorkflowTrashRetention.java:26`). 설정으로 두지 않은 이유가 주석에 있다(`:20-24`): 이 값이 응답의 `recoverableUntil` 계약에 실린다.
- 정리 작업자: `RunRetentionWorker` 가 쪽 단위로 지우고 쪽 사이마다 계속해도 되는지 다시 묻는다(`RunRetentionWorker.java:58-80`). 서버가 여러 대이면 `WorkJob` 작업 임대를 쥔 서버 한 대만 돈다(`WorkJob.java:17-38`, `ServerBootstrap.java:7174-7180`).
- n8n: Simple Memory 는 한 시간 동안 쓰이지 않은 버퍼를 지운다(`MemoryBufferWindow.node.ts:63-67`). Redis 메모리의 `sessionTTL` 기본 0 은 만료하지 않는다는 뜻이다. Postgres 메모리에는 보관 설정이 없다.
- OpenAI: 응답 객체는 30일 보관하고, 대화 객체는 30일 TTL 대상이 아니라고 적는다([대화 상태 가이드](https://developers.openai.com/api/docs/guides/conversation-state)).

### 4.2 권장 기본값 (제안)

| 설정 | 권장 기본값 | 넘었을 때 처리 | 근거 |
|---|---|---|---|
| 유휴 만료 | 마지막 사용 뒤 30일 | 읽을 때 거르고 정리기가 지운다 | OpenAI 응답 보관 30일, 기존 `dedupeTtlDays` 30 |
| 절대 만료 | 0 (끔) | 켜면 유휴 만료와 같게 처리한다 | OpenAI 대화도 TTL 대상이 아니다 |
| 세션당 메시지 수 | 1,000 | 가장 오래된 턴을 통째로 지운다 | 도구 호출과 그 결과의 짝을 깨지 않는다. 새 메시지를 거절하면 B2C 대화가 끊긴다 |
| 세션당 텍스트 바이트 | 4 MiB | 오래된 턴을 지운다. 메시지 한 건이 상한을 넘으면 413 | 조회 한 번의 비용을 묶는다 |
| 세션당 첨부 바이트 | 100 MiB | 새 첨부를 413 으로 거절한다 | 기록이 가리키는 첨부를 조용히 지우지 않는다 |
| 첨부 보관 | 세션과 함께 지운다 | 세션을 지우면 함께 지운다 | 사용자의 삭제 요청에 응하려면 첨부도 지워야 한다 |
| 세션에 붙지 않은 `/v1/files` | 24시간 | 정리기가 지운다 | 고아 조각 정리(`orphanChunkMaxAgeMinutes`)와 같은 성격 |
| 워크플로당 활성 세션 수 | 1,000,000 | 새 세션을 429 로 거절한다. 기존 세션은 내쫓지 않는다 | nonce 저장소도 가득 차면 거절하고 활성 항목을 내쫓지 않는다 |
| 익명 세션 생성 | 출발지당 분당 20 | 429 | `webhookRateLimitPerActor`(60) 와 같은 형태 |
| 정리 주기 · 쪽 크기 | 60분 · 2,000 | 쪽 사이마다 임대를 다시 확인한다 | `runRetention` 과 같은 값 |

- 정리 작업은 설치 직후부터 모든 DB 에서 켠다. 실행 기록과 달리 대화 기록은 익명 생성으로 끝없이 늘어날 수 있기 때문이다.
- 저장 상한과 모델에 보내는 문맥 창은 다른 값이다. 메모리 노드의 `contextWindowLength` 기본값은 5 다(`MemoryBufferNodeOptions.java:35-37`). 그런데 AKX 의 창 자르기(`recentWindow`)는 세션 메시지를 전부 읽은 뒤에 자른다(AKX 0.22.2 `StoreBackedChatHistoryRetriever.java:32-41`). 그래서 지금 구조에서는 저장 상한이 곧 요청 한 번의 조회 비용 상한이다. AKX 에 `recent(key, n)` 을 더하면 둘이 분리된다(앞선 문서 §2.2).

### 4.3 새 설정 키가 따를 형태 (제안)

- `server.chatSession` 중첩 객체를 `strictObject` 로 읽고 범위를 `ParseBounds` 에 등록한다(선례 `ServerConfig.java:1498`, `:1623-1629`).
- 정리기는 `WorkJob.CHAT_SESSION_RETENTION` 을 더하고 `RunRetentionWorker` 형태로 만든다.
- 보안 프로필: `SecurityProfileKnobClosureGateTest` 에 「프로필이 정하지 않는 운영 정책」으로 사유와 함께 등록하고, `open` 과 `hardened` 에 같은 값을 둔다(선례 `runRetention*`, `webhookRateLimitPerActor`).
- 반영 등급: 요청마다 읽는 상한은 LIVE, 정리를 켜고 끄는 값과 주기는 RESTART 다.
- 임베드 모드: 상한 값은 `EngineConfig` 빌더로 받고, LIVE 값은 값 공급자로 받는다(선례 `EngineConfig.java:2314`, `:2330`). 정리 주기와 쪽 크기는 서버 DB 에만 뜻이 있으므로 엔진 입구를 두지 않는다.

### 4.4 저장량 추정 (노마디안, 가정)

- 가정: 하루 활성 사용자 5만 명, 사용자당 하루 20턴, 턴당 메시지 2.5건, 건당 2 KB. 서비스 전체로 하루 약 5 GB 다.
- 텍스트: 30일 유휴 만료에서 평상시 약 150 GB. 활성 세션 25만 개가 모두 4 MiB 상한에 닿는 최악의 경우 약 1 TB.
- 첨부(별도 가정: 턴의 5% 에 500 KB): 하루 약 25 GB, 30일 약 750 GB. 저장량의 대부분을 첨부가 차지한다.
- 정리기는 매시간 약 10만 건(약 50쪽)을 지운다.

## 4.5 서버 여러 대(이중화) 환경에서의 처리 (제안, 2026-10-27 사용자 질문)

원칙은 **서버가 대화 상태를 들고 있지 않는 것**이다. 그러면 어느 서버가 요청을 받아도 같은 결과가 나온다.

| 대상 | 처리 | 근거 · 상태 |
|---|---|---|
| 대화 기록 · 세션 값 | 공유 외부 DB 에만 둔다. 서버 메모리에 캐시하지 않는다. 매 턴 DB 에서 최근 N 턴을 읽는다 | 지금 메모리 노드의 static 맵(`MemoryBufferNode.java:60`)이 이 원칙을 어긴다. 교체 대상이다 |
| 진행 중인 턴 표시 | 세션 행의 조건부 UPDATE 와 기한으로 잡는다(§2.3). 서버 메모리의 잠금을 쓰지 않는다 | 턴을 처리하던 서버가 죽으면 기한이 지나 풀린다 |
| 턴 도중 서버가 죽음 | 그 턴은 기록되지 않는다(성공한 턴만 쓴다). 클라이언트가 같은 턴 id 로 다시 보내면 다른 서버가 처리하고, 중복은 턴 id 로 막는다 | §2.3 |
| 첨부 파일 | blob 저장소가 서버 사이에 공유되어야 한다. 서버 로컬 디스크의 파일 저장소를 쓰면 다른 서버가 첨부를 읽지 못한다 | `BlobStores.forServer` 가 파일 또는 DB 저장소를 고른다(`ServerBootstrap.java:1939`). 여러 서버 배포(#2868)에서 어느 저장소를 쓰는지는 미확인 |
| 서명된 내려받기 링크 | 서명 키를 모든 서버가 같은 값으로 얻는다(공유 마스터 키에서 파생). 그래야 서버 A 가 만든 링크를 서버 B 가 검증한다 | 서명 링크 기능 자체가 아직 없다(§1.1) |
| 보관 기간 정리 | `WorkJob` 작업 임대를 쥔 서버 한 대만 돈다 | §4.3 |
| 익명 세션 생성 레이트 리미트 | 서버마다 따로 세면 서버 N 대에서 실제 상한이 N 배가 된다. 공유 카운터로 할지, 서버별 상한으로 두고 문서에 적을지 정해야 한다 | 기존 웹훅 레이트 리미트가 서버별인지 미확인 |
| 끊긴 스트리밍 이어 받기 | 1차에서 하지 않는다. 클라이언트는 다시 요청하거나, 턴이 끝난 뒤 기록 보기로 답을 읽는다 | 앞선 문서 §4 의 판정과 같다 |

- 기본 내장 DB(SQLite)는 파일 하나라 서버 여러 대가 함께 쓸 수 없다. 여러 서버 배포는 엔터프라이즈 판의 외부 DB 에서만 성립한다. 임베드 모드는 메모리 구현이므로 프로세스 하나 안에서만 유지된다.

## 5. 세 연구를 맞추며 정한 것과 결정이 필요한 것

1. **SPI 위치 (결정 필요)**: 앞선 설계는 「세션 저장소 SPI 를 node-api 에 연다」였다. 연구는 node-api 에는 노드가 쓰는 접근자만 열고, 저장소 SPI 는 엔진에 두기를 권한다. 기존 dedupe · staticData 가 이렇게 나뉘어 있고, 노드가 저장소 구현을 볼 이유가 없기 때문이다. 노드가 서버에 의존하지 않는다는 목적은 두 안 모두 지킨다.
2. **접근자는 하나로 합친다 (제안)**: 자동 기억 연구는 `NodeExecution.session()` 을, node-api 연구는 `NodeExecution.conversation()` 을 제안했다. 한 개념에 진입점 하나라는 원칙에 따라 하나로 합친다. 대화 기록(읽기 · 쓰기)과 세션 값을 한 접근자가 다루고, 이름은 `conversation()` 을 권한다. OpenAI 규격의 이름(`conversation`)과 맞기 때문이다.
3. **묶인 세션의 기록은 서버만 쓴다 (제안)**: 접근자의 쓰기(`append`)는 메모리 노드가 `customKey` 로 따로 둔 기록에만 쓴다. 실행에 묶인 세션의 기록을 노드가 쓰게 두면 서버가 턴 마감 때 쓰는 기록과 겹쳐 두 번 저장된다. 접근자는 묶인 세션에 쓰려는 호출을 거절한다.
4. **사람 단위 삭제 (결정 필요)**: CCPA 삭제 요청은 사람 단위로 온다. 캘리포니아 법무장관 FAQ 에 따르면 45일 안에 답해야 하고, 알리면 최대 90일까지 늘릴 수 있다([oag.ca.gov/privacy/ccpa](https://oag.ca.gov/privacy/ccpa)). 세션 레코드에 호출자가 준 외부 사용자 식별값을 두고, 그 값으로 그 사람의 세션을 모두 찾아 지우는 관리 API 를 둘지 정해야 한다. 권장: 둔다.
5. **삭제의 의미가 OpenAI 와 다르다 (결정 필요)**: OpenAI `DELETE /v1/conversations/{id}` 는 「대화의 항목은 지우지 않는다」고 적는다([삭제 레퍼런스](https://developers.openai.com/api/reference/resources/conversations/methods/delete)). OG 는 세션을 지울 때 기록과 첨부를 함께 지운다. 권장: OG 방식을 유지하고, 이 차이를 요건과 사용자 가이드에 적는다. 남겨 두면 사용자의 삭제 요청에 응했다고 말할 수 없다.
6. **동시 요청의 응답 (확인 필요)**: §2.3 은 같은 세션의 두 번째 요청을 409 로 거절한다. OpenAI 가 같은 대화의 동시 요청을 어떻게 처리하는지는 확인하지 못했다. 실제 API 로 시험한 뒤 정한다.

## 6. 미확인 목록

| 항목 | 확인 방법 |
|---|---|
| 실행 기록에 INLINE base64 와 바이너리 채널이 저장되는지, `persistence.runStore.maxBodyBytes` 를 넘으면 어떻게 되는지 | `RecordJsonCodec` 사용처와 실행 기록 직렬화 경로 추적 |
| `NodeExecutorAdapter` 가 `BinaryAttachment` 를 `BinaryData` 로 바꿀 때 MANAGED 와 `blob://` id 를 보존하는지 | 변환 코드 읽기와 MANAGED 시드 테스트 |
| 응답 노드 바이너리 미구현이 SRS `FR-TRIGWH-002 AC-6` 의 상태와 맞는지 | speckiwi `get_requirement` |
| 외부 플러그인이 `NodeExecution` 을 직접 구현하는지 | 배포된 `ognode-api` 사용처 조사 |
| AKX 최신 판에 멀티모달 메시지 타입이 있는지 | AKX 원본 저장소 확인 |
| ai-agent 가 메시지에 턴 id(`logicalTurnId`)를 기록하는지 | `AiAgentNode.java:136-138` 의 메타데이터 확인 |
| 크래시 회수가 에이전트를 다시 실행해 기록이 두 번 저장될 위험 | `FR-LEDGER-035` 와 회수 마커 코드 대조 |
| 일시정지 중 챗 응답 형태(요청을 열어 둘지, 진행 중 상태로 답할지) | OpenAI 백그라운드 모드 문서 |
| OpenAI 대화 `metadata` 갱신이 병합인지 교체인지, 대화의 항목 수 상한 | 실제 API 시험, API 레퍼런스 전문 |
| OpenAI Files `expires_after` 의 범위 | API 레퍼런스 |
| CCPA 보관 기간 공개 의무(§1798.100(a)(3))와 비례성 원칙(규정 §7002) | 규정 원문(이번에는 2차 자료로만 확인) |
| 노마디안의 실제 사용량(턴 수, 세션 수, 첨부 비율) | 노마디안 팀에 문의 |
| 편집기 시험 실행에 쓸 시험용 세션, 자동 기억의 기본 창 크기(메시지 수 또는 토큰 수) | 결정 필요 |
