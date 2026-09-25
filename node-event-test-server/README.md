# 노드 이벤트 테스트 서버

Node.js 22+와 Spring 백엔드가 사용하는 동일한 Redis가 필요합니다.

```sh
cd node-event-test-server
npm install
cp .env.example .env
# .env에 실제 Redis 주소, 사용자 이름, 비밀번호 설정
npm start
```

브라우저에서 **http://localhost:3001**을 열면 HTML 테스트 화면을 사용할 수 있습니다.
샘플 선택 → 이벤트 발행 → Pending 또는 DLQ 조회 순서로 확인하세요.
JSON을 직접 수정할 수 있고, 입력 문자열은 `/events/raw`로 그대로 발행됩니다.
HTML 파일을 직접 열지 말고 Express 주소로 접속하세요.

별도 터미널에서 Spring 백엔드를 실행한 다음 아래 요청을 보내세요.

```sh
# Redis 연결 확인
curl http://localhost:3001/health

# 정상 생성 이벤트: 본문 없이 샘플 발행
curl -X POST http://localhost:3001/events

# 정상 수정 이벤트
curl -X POST http://localhost:3001/events \
  -H 'Content-Type: application/json' \
  -d '{"eventType":"NODE_PATCH_REQUESTED"}'

# 미지원 버전 → DLQ
curl -X POST http://localhost:3001/events \
  -H 'Content-Type: application/json' -d '{"version":99}'

# 잘못된 이벤트 JSON → DLQ
curl -X POST http://localhost:3001/events/raw \
  -H 'Content-Type: application/json' -d '{"data":"{"}'

# Pending 50건 / 최근 DLQ 50건 조회
curl http://localhost:3001/pending
curl http://localhost:3001/dlq
```

`POST /events`의 본문은 샘플 envelope 필드를 덮어씁니다. `payload`는 객체 전체를 교체합니다.
동일한 `eventId`를 지정해 중복 이벤트도 발행할 수 있습니다.

백엔드는 정상 이벤트를 실제 `nodes` 테이블에 반영하고 `processed_node_events`에 `eventId`를 기록한 뒤 ACK합니다.
DB 커밋 후 `aideep.realtime.v1` Pub/Sub 채널로 `NODE_CREATE`/`NODE_UPDATE`도 발행하며,
실제 WS 전송은 기존 `aideep-ws`가 담당합니다. WS 서버도 같은 Redis에 연결되어 있어야 합니다.
발행 실패는 로그에 기록하고 DB 성공 및 ACK를 유지합니다. 영속 재전송은 하지 않습니다.
같은 `eventId`를 다시 보내면 노드 변경과 실시간 이벤트 발행은 한 번만 발생하고 메시지는 ACK됩니다. 잘못된 envelope/JSON, 없는 workspace/node,
`expectedVersion` 불일치는 DLQ 기록 후 ACK합니다.
정상 이벤트를 확인하려면 `workspaceId`가 실제 DB에 존재하는 워크스페이스여야 하고, patch는 존재하는 `nodeId`와
현재 `version`을 `expectedVersion`으로 보내야 합니다.
재시도 한도 경로는 processor가 일시적 실패를 발생시켜야 하므로 현재 샘플 발행만으로는 재현되지 않습니다.

이 서버는 localhost에만 열리며 메시지를 소비하거나 ACK하지 않습니다. 입력 Stream을 자동 trim하지 않습니다.
설정은 [Express 공식 문서](https://expressjs.com/en/api/)와
[node-redis 문서](https://github.com/redis/node-redis)를 참고했습니다.
