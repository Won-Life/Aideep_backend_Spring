# AI-백엔드 노드 이벤트 계약

## 목적과 상태

AI 서버가 생성한 노드 생성·부분 수정 명령을 Redis Stream으로 전달하고, 백엔드가 PostgreSQL에 안전하게 반영하기 위한
계약과 구현 방향을 정리한다.

원본 문서의 상태는 **시작 전**이다. 따라서 아래에서 **확정 계약**으로 표시한 메시지 형식과 스트림 이름은 구현 기준으로
사용하되, 원본에 없는 재시도 횟수·Consumer Group 이름·DB 스키마 등은 팀 합의 없이 외부 계약으로 확정하지 않는다.

## 한눈에 보는 처리 흐름

```text
AI server
  -> XADD onnode:ai:commands:v1 { data: "<JSON>" }
  -> backend consumer group XREADGROUP
  -> envelope and payload validation
  -> begin DB transaction
       -> processed eventId exists? skip : apply node command + save eventId
  -> commit DB transaction
  -> invalidate workspace sync cache (best effort)
  -> PUBLISH aideep.realtime.v1 (WORKSPACE_EVENT, best effort)
       -> aideep-ws broadcasts workspace_event to the workspace room
  -> XACK original message

temporary failure
  -> do not ACK
  -> keep in Pending Entries List
  -> retry or reclaim after minimum idle time

permanent failure or retry limit exceeded
  -> XADD onnode:ai:commands:dlq:v1
  -> XACK original message only after the DLQ write succeeds
```

DB 커밋 뒤 ACK 전에 프로세스가 종료되면 같은 메시지가 다시 전달될 수 있다. 이 구조는 exactly-once 전송이 아니라
**at-least-once 전달 + DB 멱등 처리**로 이해해야 한다.

## 확정 계약

### 스트림과 Redis 필드

| 용도 | Redis key | 저장 형식 |
|---|---|---|
| AI 노드 명령 | `onnode:ai:commands:v1` | Stream entry의 `data` 필드에 이벤트 JSON 문자열 저장 |
| 처리 불가 명령 | `onnode:ai:commands:dlq:v1` | 원본 이벤트를 보존하는 별도 Redis Stream |

AI 발행 예시는 `XADD`에 approximate max length `100_000`을 사용한다. 이는 생산자 측 예시값이며 백엔드가 임의로 다른
입력 스트림이나 필드명을 사용하면 안 된다.

### 공통 envelope

```json
{
  "version": 1,
  "eventId": "11111111-1111-4111-8111-111111111111",
  "eventType": "NODE_CREATE_REQUESTED",
  "occurredAt": "2026-09-21T03:30:00Z",
  "workspaceId": "22222222-2222-4222-8222-222222222222",
  "payload": {}
}
```

| 필드 | 해석과 검증 |
|---|---|
| `version` | 숫자 `1`만 지원한다. 다른 버전은 조용히 무시하지 않는다. |
| `eventId` | 이벤트 멱등성 키인 UUID다. Redis entry ID를 대신 사용하지 않는다. |
| `eventType` | `NODE_CREATE_REQUESTED` 또는 `NODE_PATCH_REQUESTED`다. |
| `occurredAt` | ISO-8601 시각이며 Java에서는 `Instant`로 역직렬화한다. |
| `workspaceId` | 명령 대상 워크스페이스 UUID다. payload 안에 중복해서 넣지 않는다. |
| `payload` | `eventType`에 맞는 DTO로 검증한다. |

### `NODE_CREATE_REQUESTED`

```json
{
  "version": 1,
  "eventId": "11111111-1111-4111-8111-111111111111",
  "eventType": "NODE_CREATE_REQUESTED",
  "occurredAt": "2026-09-21T03:30:00Z",
  "workspaceId": "22222222-2222-4222-8222-222222222222",
  "payload": {
    "title": "AI 회의 요약",
    "nodeType": "DATA",
    "position": {
      "x": 100,
      "y": 200
    },
    "data": {
      "dataType": "MARKDOWN",
      "markdownBody": "# 회의 요약\n\n회의 내용입니다.",
      "jsonBody": "{\"type\":\"doc\",\"content\":[]}",
      "color": "#ffffff",
      "textColor": "#000000"
    }
  }
}
```

현재 계약 예시가 보장하는 필드는 `title`, `nodeType`, `position.x`, `position.y`, `data.dataType`,
`data.markdownBody`, `data.jsonBody`, `data.color`, `data.textColor`이다. 허용 enum, 길이, 좌표 범위와 null 허용 여부는
원본에서 정하지 않았다.

### `NODE_PATCH_REQUESTED`

```json
{
  "version": 1,
  "eventId": "44444444-4444-4444-8444-444444444444",
  "eventType": "NODE_PATCH_REQUESTED",
  "occurredAt": "2026-09-21T03:35:00Z",
  "workspaceId": "22222222-2222-4222-8222-222222222222",
  "payload": {
    "nodeId": "55555555-5555-4555-8555-555555555555",
    "expectedVersion": 7,
    "patch": {
      "title": "수정된 회의 요약",
      "data": {
        "markdownBody": "AI가 수정한 내용",
        "jsonBody": "{\"type\":\"doc\",\"content\":[]}"
      }
    }
  }
}
```

`patch`는 전달된 필드만 바꾸는 부분 수정이다. `expectedVersion`은 동시 수정 유실을 막는 낙관적 잠금 기준으로 사용한다.
필드 누락과 명시적 `null`의 차이는 아직 계약에 없으므로 구현 전에 확정해야 한다.

### ACK와 DLQ

- 처리 성공: DB 트랜잭션 커밋 후 원본 메시지를 `XACK`한다.
- 일시적 실패: ACK하지 않고 Pending 상태로 유지해 재시도 또는 다른 consumer의 재할당 대상이 되게 한다.
- 영구적 실패 또는 재시도 한도 초과: DLQ 기록에 성공한 뒤 원본을 ACK한다.
- 한 번 실패했다는 이유만으로 즉시 DLQ로 보내지 않는다.

## 저장소에 맞춘 구현 방향

### 책임 배치

노드 명령은 `com.aideep.domain.node` 안에 응집시킨다. 실제로 필요한 계층만 만들되 다음 책임 분리를 기준으로 한다.

| 책임 | 권장 위치 | 내용 |
|---|---|---|
| 이벤트 계약 DTO | `node.dto.event` | envelope, event type, create/patch payload와 중첩 값 객체 |
| Redis 소비 설정 | `node.config` | stream key, group, consumer 이름, batch size, block/claim/retry 설정 |
| 메시지 수신 경계 | `node.service` 또는 `node.consumer` | `data` 추출, 역직렬화, 검증, 실패 분류, ACK/DLQ 조정 |
| 명령 처리 | `node.service` | 생성·수정 유스케이스와 하나의 DB 트랜잭션 경계 |
| 영속성 | `node.entity`, `node.repository` | Node와 처리된 이벤트 ID 저장·조회 |

Redis 자료구조 처리와 노드 비즈니스 규칙을 한 클래스에 섞지 않는다. 이벤트 DTO를 JPA 엔티티로 사용하지 않으며, REST 요청
DTO와도 공유하지 않는다.

워크스페이스 유효성 확인이 필요하면 `node`가 `workspace` 엔티티나 저장소를 직접 참조하지 않는다. 워크스페이스 도메인이 제공하는
서비스 또는 명시적인 조회 인터페이스를 통해 존재 여부와 삭제 상태를 확인한다.

### 소비 루프

1. 애플리케이션 시작 시 설정된 Consumer Group이 없으면 생성한다.
2. 새 메시지는 `XREADGROUP`으로 읽고, 별도 주기 작업이 오래된 pending 메시지를 reclaim한다.
3. Redis entry에서 `data` 하나를 읽고 envelope를 먼저 파싱한다.
4. `version`과 `eventType`을 확인한 뒤 해당 payload 타입으로 역직렬화하고 Bean Validation을 수행한다.
5. 하나의 DB 트랜잭션에서 `eventId` 중복 확인, 노드 명령 처리, 처리 ID 저장을 완료한다.
6. 커밋된 뒤에만 Redis ACK를 보낸다. 중복 이벤트도 이미 성공한 작업이므로 ACK한다.
7. 실패를 일시적/영구적으로 분류한다. 영구 실패나 재시도 초과는 DLQ 기록 성공 후 ACK한다.

애플리케이션 시작 스레드에서 무한 루프를 직접 돌리지 않는다. 종료 신호를 받을 수 있고 consumer 이름이 인스턴스마다 고유한
Spring Data Redis listener/container 또는 수명주기가 명확한 실행기를 사용한다.

### 멱등성 저장

처리 이력은 Redis가 아니라 PostgreSQL에 둔다. 노드 변경과 같은 트랜잭션에서 기록해야 DB 반영과 멱등성 표식이 분리되지 않는다.

권장 최소 컬럼은 다음과 같다. 실제 이름은 기존 DB 스키마와 마이그레이션 규칙을 확인한 뒤 확정한다.

- `event_id`: UUID primary key 또는 unique key
- `event_type`: 수신 event type
- `workspace_id`: 감사와 문제 추적용 UUID
- `occurred_at`: 생산 시각
- `processed_at`: 백엔드 처리 완료 시각

동시에 같은 `eventId`가 들어올 수 있으므로 사전 조회만 믿지 말고 DB unique constraint를 최종 방어선으로 둔다. unique 충돌은
이미 처리된 이벤트인지 확인한 뒤 성공으로 간주한다.

### 노드 수정 동시성

- `nodeId`가 반드시 envelope의 `workspaceId`에 속하고 삭제되지 않은 노드인지 확인한다.
- 현재 노드 버전이 `expectedVersion`과 같은 경우에만 patch를 적용하고 버전을 증가시킨다.
- 버전 불일치는 같은 메시지를 재시도해도 해결되지 않는 stale command로 분류하는 것이 기본 방향이다.
- patch의 중첩 `data`도 필드 단위 병합이며, 객체 전체 교체로 해석하지 않는다.

JPA `@Version`을 사용할지 조건부 update query를 사용할지는 노드 테이블의 기존 `version` 컬럼과 다른 API의 수정 방식까지 확인한
뒤 하나로 통일한다.

### 실패 분류 기준

| 분류 | 예시 | 처리 |
|---|---|---|
| 일시적 | DB/Redis 연결 끊김, lock timeout, 일시적 인프라 장애 | ACK하지 않고 재시도 |
| 영구적 | JSON 파싱 실패, 필수값 누락, 미지원 version/event type | DLQ 후 ACK |
| 비즈니스상 영구적 | 없는/삭제된 workspace 또는 node, workspace 불일치, stale `expectedVersion` | 오류 사유와 함께 DLQ 후 ACK |
| 중복 | DB에 같은 `eventId` 존재 | 변경 없이 ACK |

예상하지 못한 프로그래밍 오류를 곧바로 영구 실패로 확정하지 않는다. 제한 횟수까지 재시도한 후 DLQ로 보내고 원인과 stack trace를
로그에 남긴다.

### 도메인 오류 형식

노드 도메인 오류는 `NodeError implements ErrorCode`에 정의하고 `BusinessException`으로 전달한다.
독립적인 RuntimeException 하위 클래스는 사용하지 않는다. JSON/envelope/payload 계약 오류는 400,
없는 workspace/node는 404, 버전 충돌은 409에 대응하며 공통 `GlobalExceptionHandler`의 FAIL 응답을 사용한다.
Redis DLQ의 기존 문자열 코드는 새 `NODE-001`부터 `NODE-011`까지의 숫자 코드로 변경한다.
과거 DLQ 항목은 저장 당시 코드 그대로 남으므로 운영 조회 시 두 코드 체계를 해석해야 한다.
`NODE-001`~`NODE-009`는 계약·도메인 영구 실패, `NODE-010`은 누락된 stream `data`,
`NODE-011`은 재시도 가능한 처리 실패에 사용한다.

파싱 실패 시에는 `BusinessException.data`의 `NodeEventErrorContext`에 파싱 가능한 eventId/eventType과
상세 사유를 보존한다. `NodeEventWorker`는 `NodeError`를 영구 실패로 분류하고, 다른 도메인/인프라 오류는
기존처럼 재시도 대상으로 남긴다. 버전 충돌의 nodeId/expectedVersion/currentVersion도 data에 담는다.

### DLQ 데이터

DLQ에는 원본을 재처리할 수 있도록 최소한 다음 정보를 보존하는 방향을 권장한다.

- 원본 Redis stream key와 entry ID
- 원본 `data` 문자열
- `eventId`, `eventType`(파싱 가능할 때)
- 실패 분류와 안전한 오류 코드
- 누적 시도 횟수와 DLQ 이동 시각

비밀번호, 토큰, 전체 stack trace 같은 민감하거나 과도한 데이터는 DLQ payload에 넣지 않는다. DLQ `XADD` 뒤 ACK 전에 장애가
나면 DLQ 항목이 중복될 수 있으므로 운영 도구는 `eventId`와 원본 entry ID를 기준으로 중복을 식별해야 한다.

### 설정과 관측성

stream key 같은 외부 계약 기본값은 코드 상수에 흩뜨리지 않고 `node` 도메인의 설정 객체에 모은다. 다음 값은 환경별 조정이
가능해야 한다.

- Consumer Group과 consumer 이름
- read batch size와 block timeout
- pending reclaim minimum idle time
- 최대 처리 시도 횟수
- retry/reclaim 주기
- DLQ stream key와 retention 정책

로그에는 Redis entry ID, event ID, event type, workspace ID, 시도 횟수를 구조적으로 남기되 이벤트 본문 전체는 기본적으로
기록하지 않는다. 처리 성공/실패/DLQ 수, 처리 지연, pending 수와 oldest pending age를 지표로 노출한다.

### 구현된 노드 반영

Redis 수신 경계는 `node.consumer.RedisNodeEventConsumer`, envelope 파싱은 `node.service.NodeEventParser`,
실패 분류는 `node.service.NodeEventWorker`, 실제 DB 반영은 `node.service.PersistentNodeCommandProcessor` 및
`node.service.NodeCommandService`가 담당한다.

| 책임 | 클래스 |
|---|---|
| payload → 명령 DTO 변환과 계약 검증 | `node.service.NodeCommandPayloadParser` |
| 노드 생성·수정과 처리 이력 기록(한 트랜잭션) | `node.service.NodeCommandService` |
| 멱등 충돌을 성공으로 흡수 | `node.service.PersistentNodeCommandProcessor` |
| 영속성 | `node.entity.Node`, `node.entity.ProcessedNodeEvent`, `node.repository.*` |
| 워크스페이스 존재 확인 경계 | `workspace.service.WorkspaceQueryService` |

- 생성은 `title`, `nodeType`, `position.x`, `position.y`, `data`를 모두 필수로 요구하고 `data`를 `nodes.content`
  jsonb에 그대로 저장한다. `version`은 1, `depth`는 0으로 시작한다.
- 수정은 `patch`에 존재하는 필드만 반영한다. `patch.data`는 기존 `content`와 필드 단위로 병합하며 객체 전체를 교체하지
  않는다. 명시적 `null`은 삭제가 아니라 계약 위반으로 보고 영구 실패로 분류한다.
- `expectedVersion`이 현재 `nodes.version`과 다르면 `NODE-009` 영구 실패로 DLQ에 기록한다.
- 없는/삭제된 workspace는 `NODE-007`, 워크스페이스에 속하지 않거나 삭제된 노드는 `NODE-008` 영구
  실패다.
- 멱등성은 `processed_node_events` 테이블의 `event_id` primary key로 보장한다. 사전 조회로 중복을 건너뛰고, 경합으로
  unique 충돌이 나면 이미 처리된 이벤트인지 확인한 뒤 성공으로 간주한다.
- 시간 소스는 `global.config.ClockConfiguration`의 `Clock` 빈 하나로 통일한다.

새 테이블 DDL과 통합 테스트 스키마는 `src/test/resources/global/aideep-schema.sql`에 있다.

### 1차 Redis 소비 구현

Redis 수신 경계는 `node.consumer.RedisNodeEventConsumer`, envelope 파싱과 서비스 호출은
`node.service.NodeEventWorker`, 다음 단계의 노드 반영 경계는 `node.service.NodeCommandProcessor`로 구현한다.

- Consumer Group은 `0-0`에서 생성해 이미 쌓인 이벤트도 읽고, 인스턴스마다 고유한 consumer 이름을 사용한다.
- 기본 processor(`PersistentNodeCommandProcessor`)는 항상 준비 상태이므로 설정된 주기로 Pending reclaim을 수행한다.
  `DEFERRED` 경로는 준비되지 않은 processor를 위한 계약으로 남아 있으며, 테스트 대역
  `node.support.DeferredNodeCommandProcessor`가 사용한다.
- 일시적 처리 실패 횟수는 `onnode:ai:commands:retries:v1` hash에 Redis entry ID별로 기록한다. 의도적인 보류와 Redis의
  delivery count는 재시도 횟수에 포함하지 않는다.
- 기본 설정은 group `aideep-node-command-workers-v1`, batch 1, block timeout 2초, reclaim/min idle 30초, 최대 5회,
  DLQ approximate max length 100,000이며 `node.events` 설정으로 재정의할 수 있다.
- 한 인스턴스에서는 listener와 reclaim 처리를 직렬화하지만 여러 인스턴스 사이의 전역 순서는 보장하지 않는다.

## 테스트 기준

### 단위 테스트

- 두 event type의 정상 JSON 역직렬화와 중첩 payload 매핑
- 미지원 version/type, 잘못된 UUID·시각, 필수 필드 누락 거부
- patch에서 누락 필드가 유지되는지 확인
- 실패 원인의 일시적/영구적 분류
- `expectedVersion` 일치·불일치 정책

### 영속성 테스트

- 처리 이력 `eventId` unique constraint
- 노드 변경과 처리 이력 저장의 동일 트랜잭션 rollback
- node/workspace 조건과 version을 포함한 수정
- 실제 테이블·컬럼·enum·제약 조건 매핑

### 통합 테스트

Testcontainers PostgreSQL과 Redis를 사용해 다음을 검증한다.

- AI 형식의 `data` 메시지를 발행하면 노드가 반영되고 메시지가 ACK됨
- 동일 `eventId`를 두 번 전달해도 노드 변경은 한 번만 발생함
- DB 커밋 후 ACK 이전 재전달을 모사해도 결과가 중복되지 않음
- 일시적 실패는 pending에 남고 reclaim 후 성공함
- 영구 실패와 재시도 초과 메시지는 DLQ 기록 후 원본이 ACK됨
- DLQ 기록 실패 시 원본을 ACK하지 않음
- 여러 consumer가 같은 group에서 처리해도 한 이벤트가 중복 반영되지 않음

## 구현된 실시간 이벤트 버스 연동

Nest API의 `src/event-bus/event-bus.contract.ts`, `event-bus.publisher.ts`, `src/ws/ws.event.ts`와
실시간 서버의 `src/domain/event-bus/service/event-bus.subscriber.ts` 계약을 따른다.
Spring은 Redis Pub/Sub에 발행하고, `aideep-ws`가 기존 Socket.IO 워크스페이스 room으로 전달한다.
Spring과 `aideep-ws`는 같은 Redis에 연결되어야 하며 Spring에는 별도 WS endpoint가 없다.

- 채널: `aideep.realtime.v1`
- envelope: `v=1`, `kind=WORKSPACE_EVENT`, `messageId`(발행마다 생성한 UUID),
  `publishedAt`(ISO-8601), `origin=spring-api`, `payload`
- 생성 payload: `type=NODE_CREATE`, `workspaceId`, `userId=system:ai`,
  `node={nodeId,title,nodeType,position:{x,y},data,createdAt}`
- 수정 payload: `type=NODE_UPDATE`, `workspaceId`, `userId=system:ai`, `nodeId`, `patch`
- 생성 결과는 DB에 저장한 ID와 값을 사용한다. 수정의 `patch`에는 요청한 필드만 포함하며, `data`를 수정했다면
  입력 조각 대신 기존 content와 병합한 전체 객체를 전송한다. 변경하지 않은 필드는 null 없이 생략한다.
- `system:ai`는 실시간 이벤트의 발신자 표식이며 사용자 계정이나 DB 작성자 기록을 의미하지 않는다.

`NodeCommandService`가 JPA 엔티티와 분리된 `NodeRealtimeEvent` 스냅샷을 만들고,
`NodeEventBusPublisher`의 `AFTER_COMMIT` 리스너가 `workspace:sync:{workspaceId}` 캐시 삭제 후 발행한다.
롤백, 검증 실패, 이미 처리된 eventId에는 발행하지 않는다. 동시 중복 명령도 DB unique constraint에서
패배한 트랜잭션이 롤백되므로 추가 발행되지 않는다.

캐시 삭제와 발행은 각각 실패 로그를 남기며 캐시 삭제 실패가 발행 시도를 막지 않는다. 발행 실패 또는 구독자 부재로
노드 DB 처리 성공을 취소하거나 Stream을 재시도하지 않는다. 성공 명령의 ACK 정책은 유지한다.
Pub/Sub은 영속 큐가 아니므로 DB 커밋 직후 프로세스 종료, Redis 장애, WS 서버 중단 시 알림이 유실될 수 있다.
발행 성공은 클라이언트 수신 확인이 아니다. 캐시 삭제까지 실패했다면 기존 캐시가 만료될 때까지 조회가 오래된 값을
반환할 수 있다. outbox, 재전송, Yjs 문서 갱신 및 `YJS_UPDATE`는 포함하지 않는다.

`NodeRealtimeEventIntegrationTest`에서 실제 PostgreSQL·Redis로 커밋 후 발행, 채널 수신, 캐시 삭제,
롤백·중복·동시 중복, patch 직렬화, 발행 장애 시 DB 성공 및 Stream ACK를 검증한다.

### 실제 WS 서버 수동 확인

1. Spring과 기존 `aideep-ws`를 같은 테스트 Redis/DB에 연결하고, 존재하는 워크스페이스에 권한이 있는 사용자로
   기존 프런트엔드를 연다. Socket.IO `/workspace` namespace에서 `join_workspace` 성공을 확인한다.
2. [노드 이벤트 테스트 서버](../../node-event-test-server/README.md)를 실행하고 테스트 화면에서 실제
   `workspaceId`를 넣은 `NODE_CREATE_REQUESTED`를 발행한다. DB 생성과 `workspace_event`의 `NODE_CREATE`,
   프런트엔드 노드 표시를 확인한다. 필요하면 Redis CLI의 `SUBSCRIBE aideep.realtime.v1`로 envelope를 확인한다.
3. 생성된 `nodeId`와 현재 `expectedVersion`으로 `NODE_PATCH_REQUESTED`를 발행한다. `NODE_UPDATE`의 `patch`와
   화면 변경을 확인한다. 같은 eventId를 다시 보내도 추가 이벤트가 없어야 한다.
4. 다른 워크스페이스의 클라이언트에는 이벤트가 전달되지 않는지 확인한다. 에디터의 Yjs 동기화는 이 검증 대상이 아니다.

## 구현된 결과 이벤트(백엔드→AI) 계약

백엔드가 처리한 노드 명령의 최종 결과(성공/실패)를 AI 서버에 Redis Stream으로 통지한다. 입력 스트림, WS Pub/Sub,
DLQ 계약은 이 섹션으로 변경되지 않는다.

### 스트림과 Redis 필드

| 용도 | Redis key | 저장 형식 |
|---|---|---|
| 명령 처리 결과 | `onnode:ai:command-results:v1` | Stream entry의 `data` 필드에 결과 JSON 문자열 저장 |

### envelope와 payload

```json
{
  "version": 1,
  "eventId": "99999999-9999-4999-8999-999999999999",
  "eventType": "NODE_CREATE_SUCCEEDED",
  "occurredAt": "2026-09-21T03:30:05Z",
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

```json
{
  "version": 1,
  "eventId": "88888888-8888-4888-8888-888888888888",
  "eventType": "NODE_PATCH_FAILED",
  "occurredAt": "2026-09-21T03:35:05Z",
  "workspaceId": "22222222-2222-4222-8222-222222222222",
  "payload": {
    "commandEventId": "44444444-4444-4444-8444-444444444444",
    "commandEventType": "NODE_PATCH_REQUESTED",
    "error": {
      "code": "NODE-009",
      "message": "노드 버전이 일치하지 않습니다.",
      "details": {"nodeId": "55555555-5555-4555-8555-555555555555", "expectedVersion": 7, "currentVersion": 9}
    }
  }
}
```

| 필드 | 해석 |
|---|---|
| `eventId` | 결과 이벤트 자체의 UUID다. 저장·재발행에도 동일 값을 유지하는 안정적 키다. 원본 명령의 `eventId`가 아니다. |
| `eventType` | `NODE_CREATE_SUCCEEDED`, `NODE_CREATE_FAILED`, `NODE_PATCH_SUCCEEDED`, `NODE_PATCH_FAILED` 중 하나다. |
| `occurredAt` | 결과가 **최종 확정된 시각**이다(재발행 시에도 바뀌지 않는다). |
| `payload.commandEventId`/`commandEventType` | 원본 명령의 `eventId`/`eventType`이다. |
| `payload.result` | 성공일 때만 존재하며 `nodeId`, `nodeVersion`을 담는다. |
| `payload.error` | 실패일 때만 존재하며 `code`(`NodeError`), `message`, `details`를 담는다. stale 버전(`NODE-009`)은 `details`에 `nodeId`/`expectedVersion`/`currentVersion`을 포함한다. 다른 실패는 `details`가 빈 객체일 수 있다. |

### 영속성과 발행 (Outbox)

- `node_command_results` 테이블(`command_event_id` unique, `data` 텍스트 JSON, `published_at`)에 결과를 저장한다.
  성공은 `NodeCommandService.apply`의 노드 변경과 **같은 트랜잭션**에서 저장한다. 실패는 `NodeCommandResultService`가
  별도 트랜잭션에서 저장하며, `RedisNodeEventConsumer`는 DLQ 기록/ACK **전에** 이 저장을 완료한다.
- ACK는 결과 저장 완료 이후에만 보낸다. 재시도 중인 일시적 실패는 결과를 기록하지 않는다(최종 결과가 아니므로).
- `NodeCommandResultPublisher`(`SmartLifecycle`)가 설정된 주기(`node.results.publish-interval`, 기본 1초)로
  `published_at is null`인 행을 `SELECT ... FOR UPDATE SKIP LOCKED`로 하나씩 잠그고 `XADD` 후 `published_at`을 채우는
  트랜잭션을 실행한다. `XADD` 실패 시 트랜잭션이 롤백되어 다음 주기 또는 다른 인스턴스가 같은 불변 JSON을 재시도한다.
  이는 설계상 **최소 한 번(at-least-once) 발행**이며, AI 서버는 `eventId` 기준으로 멱등 처리해야 한다.
- `node.results` 설정: `enabled`, `streamKey`(기본 `onnode:ai:command-results:v1`), `publishInterval`, `batchSize`.

### 동일 명령 재요청(멱등) 시 재전달

- 같은 `commandEventId`로 다시 명령이 오면 노드를 다시 바꾸거나 새 결과를 만들지 않는다. 기존
  `node_command_results` 행을 찾아 `published_at`을 다시 `null`로 돌려 **원본 결과를 그대로 재발행**하도록 요청한다.
  AI 서버는 Stream에서 동일 `eventId`의 결과를 다시 받을 수 있으며 이는 중복이 아니라 재전달이다.
- 한 번 실패로 확정된 `commandEventId`는 다시 실행되지 않는다. `NodeCommandService.apply`는 저장된 결과에 `error`가
  있으면 같은 `NodeError`로 즉시 재실패시키고(노드도 다시 건드리지 않는다), `NodeEventWorker`는 이를 바로 영구
  실패로 분류해 DLQ 경로로 보낸다(원본 Throwable이 `TerminalNodeCommandFailure`를 지니면 `NODE-011`이어도 재시도하지
  않는다).
- `V3__add_node_command_results.sql` 이전에 처리된 `eventId`(즉 `processed_node_events`에만 존재)는 재구성 가능한
  과거 결과가 없으므로 `NodeCommandResultService.recordFailure`가 결과를 만들지 않고 `false`를 반환한다. 이 경우
  consumer는 DLQ에 기록하지 않고 조용히 ACK한다(이미 성공한 명령을 실패로 둔갑시키지 않는다).

### 레이스 보호: 이미 성공한 명령이 실패로 뒤집히지 않도록

- `recordFailure`는 저장 직전에 `command_event_id` unique 제약과 비관적 조회로 기존 행을 다시 확인한다. 같은
  `eventId`의 성공 결과가 이미 있으면(동시 재시도 중 다른 스레드가 먼저 커밋한 경우) 실패를 기록하지 않고 `false`를
  반환하며, `RedisNodeEventConsumer`는 이 경우 DLQ에 쓰지 않고 그대로 ACK한다.
- `Node` patch 경로는 `NodeRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull`에 `PESSIMISTIC_WRITE`를 사용해
  같은 노드에 대한 동시 patch가 뒤섞여 lost update를 만들지 않게 한다. 동시에 같은 `expectedVersion`으로 들어온
  두 patch 중 하나만 성공하고 다른 하나는 `NODE-009`로 영구 실패한다.

### 알려진 한계와 운영 주의점

- outbox 발행은 at-least-once이므로 Redis/프로세스 장애 시 같은 결과가 중복 발행될 수 있다. AI 서버는 `eventId`로
  멱등 처리해야 한다.
- `published_at`을 `null`로 되돌리는 재발행 요청과 `NodeCommandResultPublisher`의 조회가 동시에 실행되면 극히 짧은
  창에서 한 번 더 발행될 수 있다(at-least-once 설계상 허용).
- 과거(V3 적용 전) 명령에 대한 재요청은 결과를 재구성할 수 없어 DLQ에도 결과 스트림에도 나타나지 않고 조용히
  ACK된다. 운영팀은 이 동작을 알고 있어야 한다.

### 테스트 기준(결과 이벤트)

`NodeCommandServiceIntegrationTest`, `NodeRealtimeEventIntegrationTest`, `NodeCommandResultPublisherIntegrationTest`에서
다음을 Testcontainers PostgreSQL/Redis로 검증한다.

- 성공 결과가 노드 변경과 같은 트랜잭션에 저장되고 재요청에도 안정적으로 유지됨
- 실패 결과(계약 위반, stale 버전, 알 수 없는 workspace)가 DLQ 기록/ACK 전에 저장됨
- 같은 `commandEventId` 재요청이 노드를 바꾸지 않고 원본 결과를 재발행 요청함
- 이미 성공한 명령이 동시 레이스에서 실패로 덮이지 않음(DLQ 미기록, 결과 불변)
- `node_command_results` outbox를 `NodeCommandResultPublisher`가 비동기로 `onnode:ai:command-results:v1`에 발행함
- 동시 patch 중 하나만 성공하고 다른 하나는 `NODE-009`로 실패함

## 미결정 사항

아래는 팀 합의가 필요한 항목이다. 5~9번은 구현에서 잠정 결정했으므로 AI 서버와 계약을 맞출 때 재확인한다.

1. Consumer Group 이름과 인스턴스별 consumer 이름 규칙
2. 신규 group이 기존 메시지를 읽을지 결정하는 시작 ID (`0-0` 또는 `$`)
3. 최대 재시도 횟수, reclaim idle time, 재시도 간격
4. DLQ entry의 정확한 필드 스키마와 보관 길이·기간, 재처리 운영 절차
5. create payload 각 필드의 필수 여부, 길이·enum·좌표·색상 검증 규칙
   — 잠정: 모두 필수, title 500자, nodeType은 `node_type_enum`, 좌표는 유한한 수, 색상은 검증하지 않음
6. patch에서 명시적 `null`이 필드 삭제인지 검증 실패인지에 대한 규칙 — 잠정: 검증 실패(`NODE-005`)
7. `expectedVersion` 불일치 시 DLQ 처리 여부와 AI 서버에 결과를 알릴 별도 이벤트 필요 여부
   — 잠정: DLQ 기록 후 ACK, 역방향 결과 이벤트는 없음
8. 존재하지 않거나 삭제된 workspace/node를 영구 실패로 확정할지 여부 — 잠정: 영구 실패
9. 처리 이력 테이블명과 보관/정리 정책 — 잠정: `processed_node_events`, 정리 정책 미정
10. 입력 stream의 `MAXLEN ~ 100000`과 DLQ retention을 누가 운영할지에 대한 책임
11. AI가 만든 노드의 소유자·작성자 기록 방식 (`nodes`에 user 컬럼이 없어 현재는 기록하지 않음)

## 원본

- 문서: `AI-백엔드 서버 이벤트 계약` PDF, 4쪽
- PDF 생성 시각: 2026-09-22 17:56:30 KST
- 원래 공유된 Notion 페이지:
  `https://app.notion.com/p/AI-3e2849f59a6a8029bed5d986bb4e0868?source=copy_link`
