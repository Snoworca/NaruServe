## 목표

동일한 라우트 등록과 HTTP 요청에 대해 Express 5와 같은 공통 라우팅 결과를 제공한다. NaruServe의 편의 API·group·서비스·SSE 등을 삭제하지 않고, WebSocket은 Express 동등성 대상에서 제외하면서 기존 계약을 회귀 검증한다.

기준 oracle은 `express@5.1.0`, `router@2.2.0`, `path-to-regexp@8.4.2`, Node.js22.22.0이다. Express4 `:name(regex)` 혼합은 도입하지 않는다. 정규식 경로는 사용자 결정에 따라 Java `Pattern`을 사용하며 JavaScript RegExp 엔진 동등성·JNI는 요구하지 않는다.

## 연구 결과

현재0.9.0은 세그먼트 기반 구체성 우선 매칭과 분리된 미들웨어 목록을 사용하므로 matcher·등록스택·Dispatcher·요청수명을 함께 바꿔야 한다. 독립 연구자3개와 계획 검토자2개가 코드·실제Express실험·의존성DAG를 검토했다.

- 중복/Unicode 이름, 경로대안, Route 경계, mount별params, HEAD/OPTIONS, 오류/비동기 흐름을 차등 코퍼스로 고정한다.
- listen 이후 공통 라우트 등록, 응답 후 명시next, 반복next를 기존 제약으로 차단하지 않는다.
- TinyHttpd3.1.0의 선행 경로거부는 NaruServe에서 우회할 수 없다. 기본 보안동작을 보존하는 명시적raw-routing정책이 외부 의존성 작업으로 필요하다.
- deferred next는 owner-thread검사만 제거해서 구현할 수 없다. 요청완료·자원정리·어댑터수명을 함께 고친다.
- 실제JDK17의 현재전체기준선은1,735tests,0failures,0skips이다. 이는새0.10기능통과판정이 아니다.

연구 산출물 경로: `docs/research/express5-v010/01.research.md`, `02.tasks.md`, `tasks.json`, `evidence/`. 요구사항의 원본은 `docs/spec/`이다.

## 구현 전 게이트

- T00에서 Java Pattern 정규식 계약·ECMAScript 정규식 비동등성 경계와 나머지 공통 HTTP 적합성 분모를 고정한다. 사용자 결정 밖의 차이를 임의로 예외 처리하지 않는다.
- 외부TinyHttpd저장소변경은 사용자승인후에만 수행한다. 서명/공개배포는 별도 승인경계다.
- 현재다른내용인 `docs/next/request.md`는 덮어쓰지 않으며 대화에서확정한NaruServe범위를 적용한다.

## 완료 조건

- [ ] 아래 모든 하위작업의 인수기준이 요구별 증거로 충족된다.
- [ ] 합의한 공통 범위의 고정Express실행과 커밋fixture재생에서 설명되지않은차이가0이고, Java 고유 정규식은 별도 Pattern 계약 검증을 통과한다.
- [ ] 실제socket/Servlet·Java8/JDK17·API21·기존확장/WS·shutdown/자원검증을 통과한다.
- [ ] 최종문서/버전변경후 독립review/fix/re-review와 전체검증을 다시 통과한다.
- [ ] kiwi-orchestrator의 wave·SRS·검증·요구별증거가 남고 전체완료감사를 통과한다.

## 하위 작업

- [ ] #47 — T00 0.10.0 호환 프로파일과 변경 SRS 확정
- [ ] #48 — T01 Express 5 oracle와 차등 적합성 코퍼스 구축
- [ ] #49 — T02 Express 5 문자열 경로 tokenizer와 compiled matcher 구현
- [ ] #50 — T03 Request의 목록형 매개변수와 레이어 경로 뷰 구현
- [ ] #51 — T04 경로 대안 배열과 정규식 경로 대응 API 구현
- [ ] #52 — T05 단일 등록 스택과 Route identity로 등록 모델 교체
- [ ] #53 — T06 Dispatch cursor와 next·route/router skip 실행 구현
- [ ] #54 — T07 중첩 Router 마운트와 mergeParams 지원
- [ ] #55 — T08 경로 매개변수 전처리 콜백과 요청별 캐시 구현
- [ ] #56 — T09 HTTP 메서드·기본 정책·HEAD·OPTIONS 자동 응답 정합화
- [ ] #57 — T10 등록 순서의 오류 흐름과 final response 정합화
- [ ] #58 — T11 지연 next와 비동기 resolve/reject 요청 수명 구현
- [ ] #59 — T12 TinyHttpd 원시 경로 전달 opt-in 정책 확보
- [ ] #60 — T13 NaruServe TinyHttpd 어댑터의 raw routing·지연 응답 통합
- [ ] #61 — T14 Servlet 일반 요청의 AsyncContext 수명과 raw path 통합
- [ ] #62 — T15 group·CORS·정적 파일·서비스 확장 통합 회귀
- [ ] #63 — T16 WebSocket 기존 확장 동작 보존 검증
- [ ] #64 — T17 전체 적합성·Java8/API21·자원 회귀와 독립 개선 루프
- [ ] #65 — T18 0.10.0 한영 마이그레이션 문서와 릴리스 산출물 정리

공유 Dispatcher수정은T06→T07→T08→T09→T10→T11순서로통합하며 최종 T18의문서/버전변경후에도전체검증을반복한다.

<!-- naruserve-express5-v010:epic -->
