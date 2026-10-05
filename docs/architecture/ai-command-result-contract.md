# 백엔드 → AI 노드 명령 처리 결과 계약

## 1. 목적

AI 서버가 `onnode:ai:commands:v1`으로 보낸 `NODE_CREATE_REQUESTED` / `NODE_PATCH_REQUESTED` 명령을 백엔드가
처리한 뒤, 그 결과(성공 또는 최종 실패)를 AI 서버에 돌려주기 위한 별도 Redis Stream 계약이다.

기존에 백엔드가 WS 서버(`aideep-ws`)로 보내는 실시간 브로드캐스트(`aideep.realtime.v1`)와는 별개의 채널이다.
그 채널은 화면 갱신용이고, 이 문서의 채널은 AI가 자신이 보낸 명령의 성패를 확인하고 다음 행동(재시도, 재질의,
다음 명령 전송 등)을 결정하기 위한 채널이다.

## 2. 연결 정보

| 항목 | 값 |
|---|---|
| Redis 자료구조 | Stream |
| key | `onnode:ai:command-results:v1` |
| 저장 형식 | Stream entry의 `data` 필드에 이벤트 JSON 문자열 1개 |
| 발행 주체 | 백엔드 (`aideep` 서버) |
| 소비 주체 | AI 서버 |
| Consumer Group | AI 서버가 원하는 이름으로 직접 생성해서 사용한다. 백엔드가 그룹을 강제하지 않는다. |
| 전달 보장 | **at-least-once.** 같은 결과가 중복 도착할 수 있다. 아래 6번 "멱등 처리" 필수. |

운영 환경에서 stream key를 바꾸고 싶다면 `NODE_EVENTS_` 계열과 별도로 백엔드 쪽 설정 키를 공유받아야 한다 (현재
기본값 그대로 사용 중이며 변경 시 백엔드 팀에 알려야 한다).

## 3. 공통 envelope

```json
{
  "version": 1,
  "eventId": "99999999-9999-4999-8999-999999999999",
  "eventType": "NODE_CREATE_SUCCEEDED",
  "occurredAt": "2026-10-05T00:10:00Z",
  "workspaceId": "22222222-2222-4222-8222-222222222222",
  "payload": {}
}
```

| 필드 | 의미 |
|---|---|
| `version` | 결과 이벤트 계약 버전. 현재 `1`만 존재. |
| `eventId` | **이 결과 이벤트 자체의 UUID.** 같은 결과가 재전달되어도 값이 바뀌지 않는다. AI는 이 값으로 중복 수신을 걸러야 한다. |
| `eventType` | `NODE_CREATE_SUCCEEDED` / `NODE_CREATE_FAILED` / `NODE_PATCH_SUCCEEDED` / `NODE_PATCH_FAILED` 중 하나. |
| `occurredAt` | 백엔드가 이 명령을 최종 처리(성공 확정 또는 영구 실패 확정)한 시각. 재전송 시각이 아니다. |
| `workspaceId` | 원본 명령과 동일한 워크스페이스 UUID. |
| `payload` | 아래 4, 5번 참고. |

`eventType`은 원본 요청 타입(`NODE_CREATE_REQUESTED`/`NODE_PATCH_REQUESTED`)의 접미사 `_REQUESTED`를
`_SUCCEEDED` 또는 `_FAILED`로 바꾼 값이다.

## 4. 성공 결과

```json
{
  "version": 1,
  "eventId": "99999999-9999-4999-8999-999999999999",
  "eventType": "NODE_CREATE_SUCCEEDED",
  "occurredAt": "2026-10-05T00:10:00Z",
  "workspaceId": "22222222-2222-4222-8222-222222222222",
  "payload": {
    "commandEventId": "11111111-1111-4111-8111-111111111111",
    "commandEventType": "NODE_CREATE_REQUESTED",
    "result": {
      "nodeId": "55555555-5555-4555-8555-555555555555",
      "nodeVersion": 1
    }
  }
}
```

| 필드 | 의미 |
|---|---|
| `payload.commandEventId` | **AI가 원래 보낸 명령의 `eventId`.** 요청/응답을 연결하는 키다. |
| `payload.commandEventType` | 원본 명령 타입(`NODE_CREATE_REQUESTED` 또는 `NODE_PATCH_REQUESTED`). |
| `payload.result.nodeId` | 생성되었거나 수정된 노드의 ID. |
| `payload.result.nodeVersion` | 이 명령이 반영된 직후의 노드 버전. 이후 다른 명령으로 더 올라갈 수 있으므로 "지금 이 순간의 최신 버전"을 보장하지는 않는다. 다음 PATCH의 `expectedVersion`으로 그대로 쓰면 된다. |

CREATE/PATCH 모두 같은 구조를 쓴다. 노드 전체 내용(`title`, `data` 등)은 포함하지 않는다. 필요하면 별도
조회 API로 가져온다 (이 계약의 범위 밖).

## 5. 실패 결과

```json
{
  "version": 1,
  "eventId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "eventType": "NODE_PATCH_FAILED",
  "occurredAt": "2026-10-05T00:12:00Z",
  "workspaceId": "22222222-2222-4222-8222-222222222222",
  "payload": {
    "commandEventId": "44444444-4444-4444-8444-444444444444",
    "commandEventType": "NODE_PATCH_REQUESTED",
    "error": {
      "code": "NODE-009",
      "message": "노드 버전이 일치하지 않습니다.",
      "details": {
        "nodeId": "55555555-5555-4555-8555-555555555555",
        "expectedVersion": 7,
        "currentVersion": 8
      }
    }
  }
}
```

| 필드 | 의미 |
|---|---|
| `payload.error.code` | 아래 7번 오류 코드 표의 값. |
| `payload.error.message` | 사람이 읽는 한국어 사유. 분기 로직에 쓰지 말고 `code`를 기준으로 분기한다. |
| `payload.error.details` | 오류 코드에 따라 달라지는 구조화 정보. 현재는 `NODE-009`(버전 충돌)만 `nodeId`, `expectedVersion`,
  `currentVersion`을 채운다. 그 외 코드는 `{}`로 비어 있을 수 있다. |

**중요:** 이 실패는 "일시적으로 재시도 중"인 상태가 아니라 **더 이상 재시도하지 않기로 확정된 최종 실패**다.
백엔드가 일시적 장애로 내부 재시도 중일 때는 이 Stream에 아무것도 보내지 않는다. 즉 AI 입장에서 "아직 결과가
안 왔다"는 "아직 처리 중이거나 유실 가능성이 있다"는 뜻이고, "실패 결과가 왔다"는 "이 commandEventId로는 더
해볼 게 없으니 새 명령(새 eventId)으로 다시 시도하라"는 뜻이다.

## 6. 멱등 처리 — AI 서버가 반드시 지켜야 하는 규칙

전달 보장이 at-least-once이므로 같은 결과가 중복 도착한다. AI 서버는 다음을 지켜야 한다.

1. **`eventId`(결과 이벤트 ID) 기준으로 중복 수신을 걸러야 한다.** 같은 `eventId`를 이미 처리했다면 무시한다.
2. 같은 `commandEventId`에 대한 결과는 **내용이 바뀌지 않는다.** 성공으로 한 번 확정된 명령이 나중에 실패로
   뒤집히거나, 그 반대로 바뀌는 일은 없다. 재전송은 항상 처음과 동일한 `result` 또는 `error`를 담고 있다.
3. 같은 명령을 다시 성공시키고 싶다면(예: 다른 내용으로 PATCH) **새 `eventId`로 새 명령을 보내야** 한다. 이미
   실패/성공이 확정된 `commandEventId`를 재사용해서 요청을 다시 보내도 노드는 다시 바뀌지 않고, 기존과 같은
   결과만 다시 돌아온다.
4. `eventId`를 수신 완료 표시(ack/checkpoint)로 쓰지 않으면, 재시작 시 이미 처리한 결과를 다시 받아 중복
   처리할 수 있다.

## 7. 오류 코드 표

| 코드 | 의미 | `details` |
|---|---|---|
| `NODE-001` | 이벤트 JSON 형식 오류 | 없음 |
| `NODE-002` | envelope 형식 오류 | 없음 |
| `NODE-003` | 지원하지 않는 `version` | 없음 |
| `NODE-004` | 지원하지 않는 `eventType` | 없음 |
| `NODE-005` | payload 검증 실패 (필수값 누락, 명시적 `null` 등) | 없음 |
| `NODE-006` | 지원하지 않는 `nodeType` | 없음 |
| `NODE-007` | 존재하지 않거나 삭제된 workspace | 없음 |
| `NODE-008` | 워크스페이스에 없거나 삭제된 node (PATCH 대상) | 없음 |
| `NODE-009` | `expectedVersion` 불일치 (낙관적 잠금 충돌) | `nodeId`, `expectedVersion`, `currentVersion` |
| `NODE-011` | 백엔드 내부 처리 실패 (재시도 한도 초과 등) | 없음 |

위 표는 백엔드의 오류 코드 목록이며, 모든 오류가 결과 Stream으로 전달되는 것은 아니다.
결과 발행에는 유효한 `eventId`·`workspaceId` UUID와 지원하는 요청 `eventType`을 복원할 수 있어야 한다.
JSON 파싱 불가(`NODE-001`), 미지원 요청 타입(`NODE-004`), `data` 누락(`NODE-010`),
명령 또는 워크스페이스 UUID 누락·오류는 결과를 발행하지 않고 DLQ에만 남긴다.
AI 쪽에서는 보낸 명령에 대해
일정 시간 이상 결과가 오지 않으면 운영 채널로 문의가 필요하다 (아직 타임아웃 기준은 합의되지 않음, 8번 참고).

## 8. 현재 알려진 한계 (합의/확인 필요)

- **타임아웃 기준 없음.** "결과가 몇 초/분 안에 안 오면 유실로 간주한다" 같은 SLA는 아직 없다. 일시적 장애
  재시도 중에는 결과가 꽤 늦게(수 초~수십 초) 올 수 있다.
- **재발행 지연.** 백엔드는 결과를 DB에 먼저 커밋하고 별도 백그라운드 작업이 평균 1초 주기로 Stream에
  발행한다. 즉 결과 확정과 Stream 도착 사이에 최대 수 초의 지연이 있을 수 있다.
- **과거 이벤트는 결과가 없을 수 있다.** 이 결과 Stream을 도입하기 전에 이미 처리된 명령은 재전송해도
  결과가 새로 생기지 않는다(이미 처리된 이벤트이므로 ACK만 되고 결과는 안 온다). 새 명령부터 결과가 보장된다.
- **워크스페이스 전체 조회는 이 계약에 없다.** `nodeId`/`nodeVersion` 확인 이후 노드 상세나 워크스페이스
  전체 상태가 필요하면 별도 조회 API를 통해야 한다(현재 없음, 별도 논의 필요).

## 9. 체크리스트 (AI 서버 구현 시)

- [ ] `onnode:ai:command-results:v1`에 Consumer Group 생성 후 구독
- [ ] 수신한 `eventType`으로 success/failure 분기
- [ ] `payload.commandEventId`로 보낸 명령과 매칭
- [ ] 결과 `eventId` 기준 중복 제거(멱등 저장)
- [ ] `NODE-009` 수신 시 `details.currentVersion`으로 최신 상태 재확인 후 재질의 여부 결정
- [ ] 알 수 없는 `error.code`는 안전하게 실패로 처리(화이트리스트가 아니라 블랙리스트 금지 코드만 특별 분기)
