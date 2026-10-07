# 회의 상태 수신과 AI 맥락 전달 계약

## 목적과 상태

Recall 봇의 생명주기 상태를 웹훅으로 받아 `meetings`에 반영하고, 실제 녹음이 시작된 시점에 AI 서버로 회의 시작
이벤트를 발행한다. AI 서버는 그 이벤트를 받아 내부 API로 회의 노드의 하위 그래프를 조회해 맥락을 구성한다.

원본은 `docs/meeting-node-context.md`(AI 서버와 공유한 설명)이며, 이 문서는 저장소 구현 기준으로 다시 정리한
것이다. 상태는 **구현 완료**이며, 구현된 클래스와 설정은 아래 "구현된 결과"에 정리한다.

원본과 달라진 점: 회의 시작 이벤트의 발행 시점을 "봇 초대 직후 커밋"이 아니라 **웹훅으로 `in_call_recording`을
받아 `RECORDING`으로 전이한 시점**으로 바꿨다. 봇이 입장에 실패하면 이벤트가 발행되지 않는다. AI 서버와 이
변경을 확인해야 한다.

## 한눈에 보는 처리 흐름

```text
client
  -> POST /v1/aideep/api/meeting/bot
  -> begin DB transaction
       -> save meetings row (REQUESTED)
       -> Recall create bot (metadata.meeting_id)
       -> link botId
  -> commit DB transaction

Recall (Svix)
  -> POST /v1/aideep/api/webhooks/recall/bot-status   { bot.status_change }
  -> verify webhook-id / webhook-timestamp / webhook-signature over the RAW body
  -> begin DB transaction
       -> find meeting by bot_id
       -> Meeting.applyStatus(status, subCode, occurredAt)   (last_event_at 순서 가드)
  -> commit DB transaction
  -> RECORDING 최초 전이일 때만
       -> XADD onnode:ai:meeting-events:v1 { data: "<JSON>" }   (AFTER_COMMIT, best effort)
  -> 200

AI server
  -> consume MEETING_STARTED
  -> GET /v1/aideep/api/internal/workspaces/{workspaceId}/nodes/{nodeId}  (X-Internal-Key)
  -> 회의 노드와 하위 노드·엣지로 맥락 구성
```

`XADD` 실패는 로그만 남기고 이미 커밋된 상태 반영과 200 응답을 되돌리지 않는다. outbox·재전송이 없으므로
**이벤트는 유실될 수 있다**.

## 확정 계약

### Recall 봇 상태 웹훅 (Recall → BE)

```text
POST /v1/aideep/api/webhooks/recall/bot-status
webhook-id: msg_...
webhook-timestamp: 1733000000
webhook-signature: v1,<base64>
```

aideep 워크스페이스는 **legacy 변형**으로 이벤트를 받는다. 실제 수신 로그에서 확인한 이벤트 이름은
`bot.joining_call`, `bot.in_call_not_recording`, `bot.in_call_recording`, `bot.call_ended`, `bot.done`처럼
상태별로 나뉘며 `bot.status_change` 하나가 아니다. 헤더는 `webhook-id`/`webhook-timestamp`/`webhook-signature`를
쓴다(Svix가 `svix-*`를 이 이름으로 바꿨으므로 헤더 이름만으로 변형을 판단할 수 없다).

백엔드는 두 변형을 모두 받아 하나로 정규화한다.

| | legacy(현재 수신) | 신규 |
|---|---|---|
| 이벤트 이름 | 상태별 (`bot.in_call_recording`) | `bot.status_change` |
| 봇 식별자 | `data.bot.id` | `data.bot_id` |
| 상태 객체 | `data.data.{code,sub_code,updated_at}` | `data.status.{code,sub_code,message,created_at}` |
| 발생 시각 | `updated_at` | `created_at` |

legacy payload에 `code`가 없으면 이벤트 이름의 `bot.` 뒤를 상태 코드로 사용한다. 어느 변형으로도 읽히지 않으면
상태를 바꾸지 않고 원문 바디를 ERROR 로그에 남긴다.

```json
{
  "event": "bot.in_call_recording",
  "data": {
    "bot": { "id": "07190000-0000-4000-8000-000000000000" },
    "data": {
      "code": "in_call_recording",
      "sub_code": null,
      "updated_at": "2026-10-06T01:02:03Z"
    }
  }
}
```

```json
{
  "event": "bot.status_change",
  "data": {
    "bot_id": "07190000-0000-4000-8000-000000000000",
    "status": {
      "code": "in_call_recording",
      "sub_code": null,
      "message": null,
      "created_at": "2026-10-06T01:02:03Z"
    }
  }
}
```

- 봇 상태 이벤트는 Recall 대시보드에 등록한 엔드포인트로만 전달된다. AI 서버가 쓰는
  `recording_config.realtime_endpoints`로는 구독할 수 없으므로 백엔드 전용 웹훅 등록이 필요하다. 상태 폴링은
  Recall이 안티패턴으로 명시하므로 대체 수단으로 쓰지 않는다.
- 서명 검증은 설정된 `whsec_` 시크릿에서 접두사를 떼고 base64 디코딩한 값을 HMAC 키로 쓰며, 서명 대상 문자열은
  `{webhook-id}.{webhook-timestamp}.{rawBody}`다. **원문 바디**를 그대로 써야 하며 DTO로 바인딩해 재직렬화하면
  검증이 깨진다. 헤더에는 로테이션 중 여러 `v1,<sig>`가 공백으로 이어질 수 있으므로 전부 비교한다.
  타임스탬프가 ±5분을 벗어나면 거부한다.
- 시크릿은 `RECALL_WEBHOOK_SECRET` 환경변수로만 주입한다. 저장소에 커밋하지 않는다.

상태 코드 매핑은 도메인에 필요한 것만 한다.

| `status.code` | `MeetingStatus` |
|---|---|
| `joining_call` | `JOINING` |
| `in_waiting_room` | `WAITING_ROOM` |
| `in_call_not_recording` | `IN_CALL_NOT_RECORDING` |
| `in_call_recording` | `RECORDING` |
| `call_ended` | `CALL_ENDED` |
| `done` | `DONE` |
| `fatal` | `FAILED` |

- 그 밖의 코드(`ready`, `recording_permission_*`, `analysis_*`, `media_expired` 등)는 상태를 바꾸지 않고 로그만
  남긴다. Recall이 코드를 추가하므로 집합을 닫힌 것으로 다루지 않는다.
- `joining_call`은 "회의 참여"가 아니고 재시도로 반복될 수 있다. `started_at`은 `in_call_recording`으로만 채운다.
- `call_ended`는 미디어 준비 완료가 아니다. 완료는 `done`이다. `fatal` 이후에도 `done`이 올 수 있으므로 종료
  상태 처리는 멱등해야 하고 이미 설정된 `ended_at`을 덮어쓰지 않는다. 기존 `Meeting.applyStatus`가 이 규칙을
  이미 구현하고 있다.

응답 규칙:

| 상황 | 응답 |
|---|---|
| 정상 처리, 순서가 뒤바뀐 이벤트 무시, 모르는 상태 코드 | 200 |
| 파싱 불가 바디, 알 수 없는 `bot_id` | 200 + 로그 (영구 실패를 무한 재시도시키지 않는다) |
| 헤더 누락·서명 불일치·타임스탬프 범위 초과 | 401 |
| DB 장애 등 일시적 실패 | 5xx (Svix 재시도 대상) |

Svix 재시도는 즉시 / 5초 / 5분 / 30분 / 2시간 / 5시간 / 10시간×2이며, 5일 연속 실패하면 엔드포인트가 자동
비활성화된다.

중복·순서 방어는 별도 테이블 없이 `meetings.last_event_at`으로만 한다. `status.created_at`이 이미 반영한
시각보다 과거면 무시한다. 같은 시각의 동일 이벤트가 재전송되면 같은 상태를 다시 쓰게 되지만 결과는 바뀌지
않는다. 단 **`RECORDING` 최초 전이 판정은 `started_at == null` 여부로 하므로 재전송에도 MEETING_STARTED는 한
번만 발행된다.**

### 회의 시작 이벤트 (BE → AI)

| 용도 | Redis key | 저장 형식 |
|---|---|---|
| 회의 시작 통지 | `onnode:ai:meeting-events:v1` | Stream entry의 `data` 필드에 이벤트 JSON 문자열 저장 |

envelope는 노드 명령 계약(`ai-backend-node-event-contract.md`)과 **형식이 다르다**. 원본 공유 형식을 그대로
사용한다.

```json
{
  "version": 1,
  "type": "MEETING_STARTED",
  "eventId": "9f1d0000-0000-4000-8000-000000000000",
  "occurredAt": "2026-10-06T01:02:03Z",
  "source": "spring-api",
  "payload": {
    "meetingId": "a1b20000-0000-4000-8000-000000000000",
    "nodeId": "c3d40000-0000-4000-8000-000000000000",
    "workspaceId": "e5f60000-0000-4000-8000-000000000000",
    "botId": "07190000-0000-4000-8000-000000000000"
  }
}
```

| 필드 | 해석 |
|---|---|
| `version` | 숫자 `1`만 사용한다. |
| `type` | `MEETING_STARTED`. 노드 계약의 `eventType`과 키 이름이 다르다. |
| `eventId` | 이벤트 자체의 UUID다. AI 서버가 중복 수신을 거르는 기준이다. |
| `occurredAt` | 녹음이 시작된 시각, 즉 웹훅 `status.created_at`이다. 발행 시각이 아니다. |
| `source` | `spring-api` 고정. |
| `payload.meetingId` | `meetings.meeting_id`. |
| `payload.nodeId` | 봇을 초대할 때 지정한 회의 노드 ID. |
| `payload.workspaceId` | 회의가 속한 워크스페이스 UUID. payload 안에만 둔다. |
| `payload.botId` | Recall 봇 식별자. |

전달 보장은 **best effort**다. AI 서버는 `eventId`로 멱등 처리하되, 이벤트가 오지 않을 수도 있다는 전제에서
동작해야 한다.

### 하위 노드 조회 API (AI → BE)

```text
GET /v1/aideep/api/internal/workspaces/{workspaceId}/nodes/{nodeId}
X-Internal-Key: <INTERNAL_API_KEY>
```

| 상태 | 조건 |
|---|---|
| 200 | 요청 노드가 해당 워크스페이스에 살아 있을 때 |
| 401 | `X-Internal-Key` 누락 또는 불일치 |
| 404 | 노드가 없거나 삭제됐거나 워크스페이스가 다를 때 (`NODE-008`) |

응답은 공통 envelope를 그대로 쓴다. `nodes`에는 **요청한 노드 자신과** 엣지를 따라 도달하는 하위 노드가 모두
들어간다. 워크스페이스 전체 노드는 포함하지 않는다. 노드 객체는 `aideep-ws` WORKSPACE_EVENT의 노드 스냅샷과
같은 필드를 쓴다.

```json
{
  "resultType": "SUCCESS",
  "error": null,
  "success": {
    "nodes": [
      {
        "nodeId": "c3d40000-0000-4000-8000-000000000000",
        "title": "주간 회의",
        "nodeType": "DATA",
        "position": { "x": 120.0, "y": 40.0 },
        "data": {},
        "createdAt": "2026-10-05T10:00:00Z"
      }
    ],
    "edges": [
      {
        "edgeId": "bbbb0000-0000-4000-8000-000000000000",
        "source": "c3d40000-0000-4000-8000-000000000000",
        "target": "aaaa0000-0000-4000-8000-000000000000",
        "sourceHandle": null,
        "targetHandle": null,
        "createdAt": "2026-10-05T10:00:00Z"
      }
    ]
  }
}
```

### 내부 API 인증

- 내부 서버 간 통신이므로 사용자 JWT가 아니라 공유 시크릿 헤더 `X-Internal-Key`로 인증한다.
- 키는 32바이트 이상 랜덤 문자열이며 환경변수로만 주입한다. 저장소에 커밋하지 않는다.
- 키가 설정되지 않으면 애플리케이션 기동을 실패시킨다(fail-fast). 인증 없는 내부 API가 열려 있는 상태를 만들지
  않는다.

## 저장소에 맞춘 구현 방향

### 책임 배치

| 책임 | 위치 |
|---|---|
| 웹훅 수신 경계 | `meeting.controller.RecallWebhookController` (원문 바디를 `byte[]`/`String`으로 받는다) |
| 서명·타임스탬프 검증 | `meeting.security.RecallWebhookVerifier` |
| 웹훅 payload DTO | `meeting.dto.request.RecallBotStatusWebhook` |
| 상태 코드 매핑 | `meeting.service.RecallStatusMapper` |
| 상태 반영과 이벤트 등록 | `meeting.service.MeetingStatusService` |
| 회의 시작 이벤트 DTO | `meeting.dto.event.MeetingStartedEvent` |
| 커밋 후 Stream 발행 | `meeting.service.MeetingEventPublisher` (`@TransactionalEventListener(AFTER_COMMIT)`) |
| 웹훅·발행 설정 | `meeting.config.RecallProperties`(`webhookSecret` 추가), `meeting.config.MeetingEventProperties` |
| 엣지 영속성 | `node.entity.Edge`, `node.repository.EdgeRepository` |
| 하위 그래프 조회 | `node.service.NodeGraphQueryService` |
| 내부 조회 엔드포인트 | `node.controller.InternalNodeController` |
| 응답 DTO | `node.dto.response.NodeGraphResponse` |
| 내부 키 인증 | `global.security.InternalApiKeyFilter`, `global.config.InternalApiProperties` |

이벤트 DTO를 REST 응답이나 JPA 엔티티로 재사용하지 않는다. 노드 스냅샷 필드가 `NodeWorkspaceEvent.NodeSnapshot`과
같더라도 REST 응답은 별도 record로 둔다.

웹훅과 내부 API는 둘 다 사용자 JWT를 쓰지 않는다. `SecurityConfig`는 `anyRequest().authenticated()`로 끝나므로
`/v1/aideep/api/webhooks/**`와 `/v1/aideep/api/internal/**`를 각각 별도 `SecurityFilterChain`으로 분리하고
자체 인증(서명 검증 / 공유 키)만 적용한다.

### 회의 초대 흐름 수정

현재 `MeetingService.inviteBot`에는 웹훅 처리와 이벤트 발행의 전제를 깨뜨리는 결함이 있어 함께 고친다.

1. `RecallBotClient.invite`가 돌려준 botId를 `Meeting.linkBot`으로 저장하지 않아 `meetings.bot_id`가 항상 null이다.
   웹훅은 `bot_id`로 회의를 찾으므로 지금 구조에서는 어떤 상태 이벤트도 매칭되지 않는다.
2. `Instant.now()`를 직접 쓰고 있어 주입된 `Clock` 빈을 무시한다. `ClockConfiguration`의 `Clock`으로 교체한다.
3. `@Transactional`이 없어 `AFTER_COMMIT` 리스너가 성립하지 않는다. 트랜잭션 경계를 만든다.
4. 저장이 Recall 호출보다 먼저인 것은 유지한다(웹훅이 `bot_id` 저장보다 먼저 도착하는 것을 막기 위함). 다만
   초대 실패 시 `REQUESTED` 행이 남지 않도록 같은 트랜잭션에서 롤백되게 정리한다.
5. 봇 `metadata`에 `meeting_id`를 추가한다. 응답 처리 실패로 `bot_id`를 저장하지 못한 봇을 나중에 회의로
   되짚을 수단이 없다.

### 웹훅 처리

- 컨트롤러는 원문 바디를 그대로 받아 검증한 뒤에만 파싱한다. `@RequestBody` DTO 바인딩을 쓰지 않는다.
- 검증 통과 후 `bot_id`로 회의를 찾고, 모르는 봇이면 로그 후 200을 돌려준다.
- `Meeting.applyStatus(...)`가 `true`를 돌려주고 `RECORDING`으로의 최초 전이일 때만 `MeetingStartedEvent`를
  등록한다. 상태를 바꾸지 않는 코드, 순서가 뒤바뀐 이벤트, 재전송에는 등록하지 않는다.
- 처리 자체는 짧으므로 동기로 두되, 느려지면 2xx를 먼저 돌려주고 비동기로 돌리는 구조를 검토한다.

### 하위 그래프 조회

- `edges` 테이블은 `V1__baseline.sql`에 이미 존재하므로 새 마이그레이션은 필요 없다. 다만
  `src/test/resources/global/aideep-schema.sql`에 `edges`가 빠져 있어 `Edge` 엔티티를 추가하면
  `ddl-auto=validate`가 무관한 도메인의 `@DataJpaTest`까지 깨뜨린다. 같은 변경에서 테스트 스키마에 `edges`를
  추가한다.
- 탐색은 Postgres 재귀 CTE 한 번으로 수행한다. 애플리케이션에서 depth마다 조회하는 BFS는 쓰지 않는다.
- 깊이 상한은 두지 않는다. 대신 이미 방문한 노드를 다시 확장하지 않아 순환 엣지에서 무한 루프가 생기지 않게 한다.
- `nodes.deleted_at`과 `edges.deleted_at`이 null인 행만 따라간다. 모든 노드·엣지는 요청 `workspaceId`로 제한한다.

### 오류 코드

새로 필요한 `MeetingError` 항목은 기존 `MEETING-001~003` 다음 번호를 이어서 쓴다. 내부 API의 노드 조회 실패는
기존 `NodeError.NODE_NOT_FOUND`(NODE-008)를 재사용하고, 내부 키 오류는 `GlobalErrorCode.UNAUTHORIZED`를 쓴다.
웹훅 응답은 Recall이 본문을 읽지 않으므로 상태 코드가 계약의 전부다.

## 테스트 기준

### 단위 테스트

- `RecallWebhookVerifier`: 정상 서명, 헤더 누락, 서명 불일치, 다중 `v1,<sig>` 로테이션, 타임스탬프 범위 초과
- `RecallStatusMapper`: 매핑 대상 코드 전부와 알 수 없는 코드의 무변경
- `MeetingStatusService`: `RECORDING` 최초 전이에만 이벤트 등록, 재전송·역순 이벤트에 미등록, `fatal` 뒤 `done`
- `MeetingServiceTest`: botId 연결, `metadata.meeting_id`, 고정 `Clock`, 초대 실패 시 롤백
- `MeetingEventPublisher`: envelope 필드와 발행 실패 시 예외 전파 없음
- `InternalApiKeyFilter`: 헤더 누락·불일치·정상

### 영속성 / 통합 테스트

- `RecallWebhookController`: 서명 불일치 401, 알 수 없는 봇 200, 정상 이벤트로 `meetings` 상태·`started_at` 반영
- 회의 시작 이벤트: Testcontainers Redis로 커밋 후 `XADD` 확인, 발행 실패해도 웹훅 응답은 200
- `NodeGraphQueryService`: 하위 노드 수집, 삭제된 노드·엣지 제외, 다른 워크스페이스 노드 404, 순환 엣지 종료
- 내부 API: 키 누락 401, 정상 200과 공통 envelope 형태

## 구현된 결과

| 책임 | 클래스·파일 |
|---|---|
| 웹훅 수신 경계(원문 바디 검증 후 파싱) | `meeting.controller.RecallWebhookController` |
| 서명·타임스탬프 검증 | `meeting.security.RecallWebhookVerifier` |
| 두 변형 payload 정규화 | `meeting.dto.request.RecallBotStatusWebhook` |
| 상태 코드 매핑 | `meeting.service.RecallStatusMapper` |
| 상태 반영과 이벤트 등록 | `meeting.service.MeetingStatusService` |
| 회의 시작 이벤트 DTO | `meeting.dto.event.MeetingStartedEvent` |
| 커밋 후 Stream 발행 | `meeting.service.MeetingEventPublisher` |
| 봇 초대와 botId 연결 | `meeting.service.MeetingService`, `meeting.service.RecallBotClient` |
| 설정 | `meeting.config.RecallProperties`, `meeting.config.MeetingEventProperties` |
| 엣지 영속성 | `node.entity.Edge`, `node.repository.EdgeRepository` |
| 하위 그래프 조회 | `node.service.NodeGraphQueryService`, `NodeRepository.findReachableNodeIds` |
| 내부 조회 엔드포인트와 응답 | `node.controller.InternalNodeController`, `node.dto.response.NodeGraphResponse` |
| 노드 존재 확인 경계 | `node.service.NodeQueryService` |
| 내부 키 인증 | `global.security.InternalApiKeyFilter`, `global.config.InternalApiProperties` |
| 진행 중 회의 URL 유일성 | `src/main/resources/db/migration/V8__add_meetings_active_url_unique.sql` |

- 필요한 환경변수: `RECALL_WEBHOOK_SECRET`, `INTERNAL_API_KEY`(32바이트 이상, 없으면 기동 실패).
  `MEETING_EVENTS_ENABLED`·`MEETING_EVENTS_STREAM_KEY`는 기본값으로 동작한다.
- 봇 초대는 회의 저장 → Recall 생성 → botId 연결을 한 트랜잭션에서 수행한다. `saveAndFlush`로 진행 중 URL
  unique 인덱스를 Recall 호출 전에 확인하므로, 중복 초대로 봇이 만들어지지 않는다.
- 봇 초대 오류: 권한 없음 403 `COMMON403`, 없는 워크스페이스 404 `MEETING-005`, 없는/다른 워크스페이스의 노드
  404 `MEETING-006`, 이미 봇이 참여 중인 링크 409 `MEETING-007`.
- 하위 그래프 탐색은 재귀 CTE 한 번으로 노드 ID를 모으고 노드·엣지를 각각 한 번 조회한다(깊이와 무관하게 쿼리 3개).
  엣지는 양쪽 끝이 모두 응답 `nodes`에 있는 것만 내보낸다. 삭제된 노드나 다른 워크스페이스 노드를 가리키는 엣지를
  남기면 AI 서버가 끊어진 참조를 받는다.
- `nodes.content`(jsonb)는 문자열이 아니라 객체로 직렬화하며, 비어 있으면 빈 객체를 보낸다.

## 미결정 사항

1. 웹훅 엔드포인트 경로(`/v1/aideep/api/webhooks/recall/bot-status`)와 Recall 대시보드 등록·시크릿 전달 주체
2. Stream key `onnode:ai:meeting-events:v1`과 envelope 키 이름(`type`/`source`)을 AI 서버와 최종 확인
3. 발행 시점을 `RECORDING` 전이로 바꾼 것을 AI 서버와 합의
4. 회의 종료(`MEETING_ENDED`) 등 후속 이벤트 필요 여부
5. 이벤트 유실을 허용할지, 추후 노드 결과 outbox처럼 보장 경로를 둘지
6. 하위 그래프에 포함할 범위(엣지 방향, 노드 타입 필터) 조정 필요 여부
7. `INTERNAL_API_KEY`·`RECALL_WEBHOOK_SECRET` 배포·회전 절차와 두 서버 간 공유 방식

## 원본

- `docs/meeting-node-context.md` (AI 서버와 공유한 설명)
- Recall 웹훅 변형·서명·상태 코드 규칙은 Recall 공식 문서 기준
