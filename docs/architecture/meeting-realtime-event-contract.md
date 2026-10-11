# 회의 상태 실시간 전파 계약 (BE → aideep-ws → FE)

## 목적과 상태

회의 봇의 진행 상황을 프런트엔드에 실시간으로 보여주기 위해, 백엔드가 기존 실시간 이벤트 버스
`aideep.realtime.v1`에 회의 이벤트를 발행한다. WebSocket 연결과 workspace room 라우팅은 `aideep-ws`가
담당하며 백엔드는 "무슨 일이 일어났다"는 사실만 발행한다.

- 근거 이슈: [#9 Feat : 회의 데이터 WS 이벤트로 실시간 전파](https://github.com/Won-Life/Aideep_backend_Spring/issues/9)
- 상태: 백엔드 구현 완료. 단위·슬라이스 테스트는 통과했고, Testcontainers 통합 테스트는 작성했으나 Docker 미기동으로 아직
  실행 검증하지 못했다. WS 서버·FE 연동은 대기 중.
- 선행 계약: [회의 상태 수신과 AI 맥락 전달 계약](meeting-ai-context-contract.md),
  [AI-백엔드 노드 이벤트 계약](ai-backend-node-event-contract.md)(동일 채널의 `WORKSPACE_EVENT` 선례)

## 한눈에 보는 처리 흐름

```text
[사용자] --POST /meeting/bot--> [MeetingService.inviteBot]
                                      | 회의 행 저장 + Recall 봇 생성 + linkBot
                                      | (커밋)
                                      +--AFTER_COMMIT--> MEETING_BOT_REQUESTED

[Recall] --봇 상태 웹훅--> [MeetingStatusService.apply]
                                      | applyStatus (멱등, lastEventAt 단조 가드)
                                      | (커밋)
                                      +--RECORDING 최초 전이--> MEETING_BOT_JOINED
                                      +--DONE 최초 전이-------> MEETING_BOT_LEFT
                                      +--FAILED 최초 전이-----> MEETING_BOT_FAILED

               모든 발행 --> [MeetingRealtimeEventPublisher] --PUBLISH--> aideep.realtime.v1
                                                                              |
                                                            [aideep-ws] --emit--> workspace room --> [FE]

[FE] --재접속/새로고침--> GET /v1/aideep/api/meeting?workspaceId=... --> 진행 중 회의 목록으로 상태 복구
```

## 확정 계약

### 전송 경로

| 항목 | 값 |
|---|---|
| 채널 | `aideep.realtime.v1` (Redis Pub/Sub, 노드 이벤트와 공유) |
| envelope `kind` | `WORKSPACE_EVENT` |
| envelope `origin` | `spring-api` |
| 전달 보장 | best-effort. 구독자가 없으면 유실되며 재전송하지 않는다. |

envelope 모양은 노드 실시간 이벤트와 동일하다(`v`, `kind`, `messageId`, `publishedAt`, `origin`, `payload`).
구분은 `payload.type`으로만 한다.

### payload type

| `payload.type` | 발행 시점 | 1회 발행 보장 근거 |
|---|---|---|
| `MEETING_BOT_REQUESTED` | `inviteBot` 트랜잭션 커밋 후 | 초대 API 호출 1건당 1회 |
| `MEETING_BOT_JOINED` | 상태가 `RECORDING`으로 **최초** 전이할 때 | `startedAt == null` 선검사 |
| `MEETING_BOT_LEFT` | 상태가 `DONE`으로 **최초** 전이할 때 | 직전 상태 `!= DONE` 선검사 |
| `MEETING_BOT_FAILED` | 상태가 `FAILED`로 **최초** 전이할 때 | 직전 상태 `!= FAILED` 선검사 |

`CALL_ENDED`는 전파하지 않는다. 사용자에게 알리는 "퇴장"은 전사 결과까지 준비된 `DONE` 시점이다.

### payload 필드

```json
{
  "type": "MEETING_BOT_JOINED",
  "workspaceId": "uuid",
  "userId": "uuid",
  "meetingId": "uuid",
  "nodeId": "uuid",
  "botId": "uuid",
  "status": "RECORDING",
  "occurredAt": "2026-10-09T02:51:25.449Z"
}
```

| 필드 | 해석 |
|---|---|
| `type` | 위 표의 4개 값 중 하나. |
| `workspaceId` | 라우팅 대상 워크스페이스. WS 서버가 room을 고르는 유일한 기준이다. |
| `userId` | 봇을 **초대한 사용자**(`meetings.user_id`). 노드 이벤트의 `"system:ai"` 자리와 같은 의미다. |
| `meetingId` | `meetings.meeting_id`. |
| `nodeId` | 봇을 초대할 때 지정한 회의 노드 ID. FE가 노드 카드에 상태를 붙이는 기준. |
| `botId` | Recall이 생성한 봇 UUID. `MEETING_BOT_REQUESTED`에서도 `linkBot` 이후 발행하므로 항상 채워진다. |
| `status` | 발행 시점의 `MeetingStatus` 문자열. |
| `occurredAt` | 상태 이벤트는 Recall 웹훅의 상태 발생 시각, 초대는 회의 행 생성 시각. 발행 시각이 아니다. |
| `statusSubCode` | **`MEETING_BOT_FAILED`에만** 포함한다. Recall `status.sub_code` 원문이며 FE 에러 메시지 분기용이다. |

payload는 자족적이어야 한다. `aideep-ws`는 DB를 조회하지 않으며, `targetRoom`·`socketId` 같은 전송 지시는
넣지 않는다(라우팅은 WS 서버의 모델이다).

### 조회 경로 (재접속 복구)

Pub/Sub은 구독자가 없으면 메시지를 버리므로 실시간 계약은 조회 경로와 함께 나간다.

| 항목 | 값 |
|---|---|
| 엔드포인트 | `GET /v1/aideep/api/meeting?workspaceId={uuid}` |
| 인증 | 사용자 JWT |
| 권한 | 해당 워크스페이스 `VIEW` 이상 (`WorkspacePermissionService.requirePermission`) |
| 응답 | 진행 중(`MeetingStatus.active()`, `deletedAt is null`) 회의 목록 |
| 항목 필드 | `meetingId`, `nodeId`, `botId`, `status`, `statusSubCode`, `startedAt`, `createdAt` |

응답은 `ResponseWrappingAdvice`가 공통 envelope(`resultType`/`error`/`success`)로 감싼다.

## 구현 방향

- 발행은 `MeetingRealtimeEventPublisher`(신규)가 `@TransactionalEventListener(AFTER_COMMIT)`로 받아
  `StringRedisTemplate.convertAndSend`로 내보낸다. 기존 `MeetingEventPublisher`(AI용 Redis **Stream**)와는
  대상·보장 수준이 다르므로 클래스를 합치지 않는다.
- 발행 실패는 ERROR 로그만 남기고 삼킨다. 이미 커밋된 회의 상태나 웹훅 200 응답을 되돌리지 않는다.
- `MeetingService.inviteBot`은 이미 `@Transactional`이므로 AFTER_COMMIT 리스너가 동작한다. 발행은
  `linkBot` 이후에 트리거해 `botId`가 비지 않게 한다.
- `MeetingStatusService.apply`는 `applyStatus` **전에** 직전 상태와 `startedAt`을 읽어 최초 전이를 판정한다.
  `Result` enum에 `MEETING_ENDED`, `MEETING_FAILED`를 추가해 컨트롤러 로그의 `result=` 필드로 분기를 드러낸다.
- envelope record는 현재 `node/dto/event/NodeEventBusEnvelope`에 있다. 두 도메인이 공유하게 되므로
  `global`로 승격해 payload 타입만 제네릭/인터페이스로 받는 형태를 권장한다(아래 미결정 사항 참고).
- 조회 API는 `MeetingQueryService` + `MeetingRepository.findByWorkspaceIdAndStatusInAndDeletedAtIsNull`로
  구현한다. `MeetingStatus.active()`를 그대로 써서 부분 유니크 인덱스 조건과 집합이 어긋나지 않게 한다.

## 구현 위치

| 역할 | 클래스 |
|---|---|
| 공통 envelope (노드·회의 공유) | `global/event/RealtimeEventEnvelope` |
| WS payload | `meeting/dto/event/MeetingWorkspaceEvent` |
| 커밋 후 발행 트리거 | `meeting/dto/event/MeetingRealtimeEvent` |
| Pub/Sub 발행 | `meeting/service/MeetingRealtimeEventPublisher` |
| 요청 이벤트 트리거 | `MeetingService.inviteBot` |
| 입장·퇴장·실패 트리거 | `MeetingStatusService.apply` |
| 조회 API | `MeetingController.findActiveMeetings` → `MeetingQueryService` |

테스트: `MeetingStatusServiceTest`, `MeetingServiceTest`, `MeetingRealtimeEventPublisherTest`,
`MeetingControllerTest`, `MeetingRealtimeEventIntegrationTest`(Postgres+Redis Testcontainers).

## 미결정 사항

- **WS 서버·FE의 수용 여부**: `aideep-ws`가 `payload.type`을 화이트리스트로 검사한다면 새 4개 타입을 추가해야
  하고, FE에도 핸들러가 있어야 백엔드 발행이 의미를 갖는다. WS 서버 담당자 확인 필요.
- **envelope 공유 방식**: `NodeEventBusEnvelope`를 `global/event/RealtimeEventEnvelope`로 승격해 두 도메인이
  공유하도록 정리했다(JSON 모양은 그대로). 추가 도메인이 같은 채널을 쓰면 이 record를 재사용한다.
- **`FAILED` 후 `DONE` 순서**: Recall은 `fatal` 이후에도 `done`을 보낼 수 있다. 현재 설계는 두 이벤트를 모두
  발행한다(실패 토스트 후 종료 표시). FE에서 어색하면 `FAILED` 이후 `LEFT`를 억제하는 규칙이 필요하다.
- **조회 API 범위**: 지금은 진행 중 회의만 반환한다. 종료된 회의 이력까지 필요하면 `status` 필터 파라미터를
  추가해야 한다.

## 테스트 기준

- 단위: `MeetingStatusService`가 `RECORDING`/`DONE`/`FAILED` **최초** 전이에서만 이벤트를 발행하고, 재전송된
  같은 웹훅에서는 발행하지 않는다.
- 단위: 발행 중 Redis 예외가 터져도 전파되지 않고, 커밋된 상태와 200 응답이 유지된다.
- Redis 통합(Testcontainers): 실제 엔드포인트를 구동해 `aideep.realtime.v1` 구독으로 받은 JSON을 필드 단위로
  검증한다. 계약에 **없어야 하는** 키(`targetRoom` 등)가 없는지도 함께 단언한다.
- Postgres 통합: `GET /meeting`이 진행 중 회의만, 권한 있는 워크스페이스에 대해서만 반환한다(권한 없음 403,
  삭제된 회의 제외).
