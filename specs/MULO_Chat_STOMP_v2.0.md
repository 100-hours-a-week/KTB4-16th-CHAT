# MULO Chat Server STOMP 명세 v2.0

## 범위와 소유권

- 이 문서는 Chat Server 소유의 WebSocket + STOMP 실시간 메시지 계약이다.
- 기존 HTTP 채팅 API와 Backend 연동 계약은 각 API 명세를 따른다. #330의 새 sync 계약은 BE 저장소의 통합 OpenAPI v2.8 후속 문서 PR과 아래 HTTP 복구 절을 따른다. 과거 이력 API의 전체 계약 정합성은 이번 변경 범위가 아니다.
- Chat Server 전용 로그인은 없다. 사용자 인증은 Backend Access Token 검증 결과를 사용한다.
- 채팅방은 매칭된 두 명만 참여하는 1:1 구조다.
- 메시지는 Chat Server MySQL에 저장한다. #330에서는 DB COMMIT 성공 후 Redis Pub/Sub으로 태스크 간 전달하고 각 태스크가 로컬 접속자에게 전송한다.
- 추가 계약의 기준은 [CHAT #36](https://github.com/100-hours-a-week/KTB4-16th-CHAT/pull/36) / [FE #147](https://github.com/100-hours-a-week/KTB4-16th-FE/pull/147)이며 기능 PR 미병합·운영 미배포 상태다. 기존 payload·user destination·인증 및 참가자 권한은 유지한다.

## 연결 및 인증

### WebSocket Handshake

- Chat Server가 허용한 Origin만 Handshake를 통과시킨다.
- Handshake 성공은 사용자 인증 성공을 의미하지 않는다.
- WebSocket Handshake URL은 배포/API Gateway 경로가 확정된 뒤 연결 설정 문서에 기록한다. 이 명세에서 경로를 임의로 정하지 않는다.

### STOMP CONNECT

```text
CONNECT
Authorization: Bearer <access-token>
```

- Access Token은 필수이며 Backend `POST /internal/auth/verify`로 검증한다.
- Backend 응답의 검증된 `userId`를 Principal에 설정한다. 요청 Body의 사용자 ID는 인증 근거로 사용하지 않는다.
- 토큰 서명, 만료, `tokenType=access`, 사용자 활성 상태를 확인한다.
- 인증 실패 시 CONNECT를 거부한다.
- 재연결 때마다 새 Access Token을 다시 검증한다. 만료 토큰은 사용할 수 없다.
- Access Token 만료 시 연결을 종료한다. 다른 기기 로그아웃으로 인한 즉시 연결 종료는 Backend의 Access Token 폐기 미지원 때문에 V2 범위가 아니다.

## 인가

### SUBSCRIBE

- 클라이언트는 아래 user destination만 구독할 수 있다.
  - `/user/queue/chat-messages`
  - `/user/queue/chat-events`
  - `/user/queue/chat-errors`
- 허용 목록 밖의 destination 및 다른 사용자에 속한 destination은 거부한다.
- 서버가 채팅방별 destination을 추가하는 경우 구독마다 Principal과 참가자 권한을 확인해야 한다. 본 v2.0 계약에서는 채팅방별 공용 destination을 사용하지 않는다.

### SEND

- 메시지 전송 destination은 `/app/chat-rooms/{chatRoomId}/messages`다.
- 모든 SEND에서 Principal 사용자, 참가자 여부, `joined_at`, `left_at`, 상대 참가자의 `left_at`, `expires_at`을 확인한다.
- 양쪽 참가자 모두 입장 완료·미퇴장이고 채팅방이 만료 전일 때만 전송한다.
- 채팅방이 만료됐거나 상대가 퇴장한 경우 전송을 거절한다.
- 본문은 500자 이하여야 한다. `userId`는 본문에 포함하지 않으며, 포함되더라도 서버는 신뢰하지 않는다.

## 메시지 전송

### 요청

```text
SEND /app/chat-rooms/{chatRoomId}/messages
content-type: application/json
```

```json
{
  "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
  "content": "안녕!"
}
```

- `clientMessageId`는 클라이언트가 생성하는 UUID다.
- 서버는 인증/권한을 확인한 뒤 `clientMessageId` 중복 여부를 확인하고 신규 메시지를 MySQL에 저장한다.
- DB COMMIT 성공 후 Redis에 발행한다. 각 구독 태스크는 자기 로컬 접속자를 필터링하고 수신자의 참가 상태를 전달 직전에 다시 확인한 뒤 기존 사용자별 큐로 결과를 보낸다. 확인 시점에 퇴장한 수신자에게는 전달하지 않는다.
- SEND가 유효하게 COMMIT된 경우, 전달 시점에 채팅방 만료 시각이 지났더라도 MESSAGE 전달을 허용하며 전달 단계에서 만료 여부를 다시 확인하지 않는다.
- 발신자도 같은 결과를 수신하며 `clientMessageId`로 전송 성공을 확인한다. 별도의 ACK destination은 없다.

### 성공 이벤트

Destination: `/user/queue/chat-messages`

```json
{
  "type": "MESSAGE",
  "chatRoomId": 30,
  "chatMessageId": 1001,
  "clientMessageId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": 17,
  "content": "안녕!",
  "createdAt": "2026-10-13T15:30:00+09:00"
}
```

### 멱등 재전송

- 식별 키는 `(userId, clientMessageId)`이며 DB의 `UNIQUE(user_id, client_message_id)`를 유지한다.
- 같은 사용자·ID·채팅방·내용의 재요청이면 기존 DB 메시지 결과를 발신자에게만 다시 보낸다. 중복 저장하지 않으며 상대방에게 다시 전파하지 않는다.
- 같은 사용자·ID지만 채팅방 또는 내용이 다르면 충돌이다.
- `clientMessageId`가 다르면 내용이 같아도 새 메시지다.

## 퇴장 이벤트

- HTTP 퇴장 API가 `left_at`을 기록한 뒤 남은 참가자에게 이벤트를 보낸다.
- 퇴장 요청이 유효한 채팅방에서 정상 처리되어 DB에 커밋된 경우, 이벤트 전달 시점에 채팅방 만료 시각이 지났더라도 남은 참가자에게 이벤트를 전달한다. 퇴장 커밋 시점의 유효성 검증을 기준으로 하며, 전달 시 채팅방 만료 여부는 다시 검사하지 않는다.
- 퇴장한 사용자는 이후 채팅방 메시지·이벤트를 구독할 수 없고 메시지를 보낼 수 없다. Frontend는 자신의 STOMP 구독도 해제한다.
- 수신 권한은 서버가 매 이벤트 전달 시 확인하며 Frontend의 UNSUBSCRIBE에만 의존하지 않는다.
- 전달 시점에 상대 참가자가 이미 퇴장한 상태라면 이벤트를 보내지 않는다.

Destination: 남은 참가자의 `/user/queue/chat-events`

```json
{
  "type": "PARTICIPANT_LEFT",
  "chatRoomId": 30,
  "userId": 28,
  "nickname": "lulu",
  "leftAt": "2026-10-13T16:00:00+09:00"
}
```

퇴장 이벤트는 퇴장한 본인에게 다시 보내지 않는다. 채팅방별 공용 topic으로 전송하지 않는다.

## STOMP 오류

오류 destination: `/user/queue/chat-errors`

```json
{
  "code": "CLIENT_MESSAGE_ID_CONFLICT",
  "message": "이미 다른 메시지에 사용된 clientMessageId입니다.",
  "clientMessageId": "550e8400-e29b-41d4-a716-446655440000"
}
```

| 상황 | 오류 코드 | 처리 |
|---|---|---|
| 전달 경로 준비 전·장애·종료 중 신규 SEND | `CHAT_DELIVERY_UNAVAILABLE` | 임시 전송 불가. 소켓이 이미 종료된 경우 큐 오류 수신까지 보장하지 않으며 재연결 후 DB 저장 여부 확인 |
| CONNECT 인증 실패 | `UNAUTHORIZED` | CONNECT 거부 |
| 지원하지 않는 STOMP 명령 | `COMMAND_NOT_ALLOWED` | 명령 거부 |
| 허용되지 않은 SUBSCRIBE | `STOMP_DESTINATION_FORBIDDEN` | 구독 거부 |
| 허용되지 않은 SEND Destination | `STOMP_DESTINATION_FORBIDDEN` | SEND 거부 |
| 채팅방이 유효하고 참가자가 2명이며, 활성 발신자에 대해 상대방이 아직 입장하지 않음 (`joined_at IS NULL`, `left_at IS NULL`) | `CHAT_PEER_NOT_JOINED` | `상대방이 아직 채팅방에 입장하지 않았습니다.` 메시지를 저장하거나 전달하지 않음 |
| 그 외 참가자/입장/퇴장 상태로 SEND 불가 | `CHAT_SEND_FORBIDDEN` | 메시지를 저장하거나 전달하지 않음 |
| 채팅방 만료 | `CHAT_ROOM_EXPIRED` | 메시지를 저장하거나 전달하지 않음 |
| 같은 ID를 다른 방 또는 내용에 재사용 | `CLIENT_MESSAGE_ID_CONFLICT` | 기존 메시지를 변경하지 않음 |
| 메시지 500자 초과 | `MESSAGE_TOO_LONG` | 메시지를 저장하거나 전달하지 않음 |
| 필수 필드 누락 또는 요청 본문 형식 오류 | `INVALID_INPUT_VALUE` | 메시지를 저장하거나 전달하지 않음 |

STOMP 오류는 HTTP 상태 코드가 아니다. 실패 메시지에 별도 ACK destination을 추가하지 않는다.

`CHAT_SEND_FORBIDDEN` 응답은 기본적으로 기존 `code`, `message`, `clientMessageId` 필드만 포함한다. `CHAT_STOMP_SEND_DIAGNOSTICS_ENABLED=true`와 `CHAT_DEPLOYMENT_ENVIRONMENT=development`가 모두 설정되고 운영 프로필(`prod`, `production`)이 활성화되지 않은 경우에만 제한된 `debug` 객체(`stage`, 허용된 `reason` 코드)를 추가할 수 있다. 배포 환경 표식이 없거나 개발 환경이 아니면 진단 응답은 비활성화되며 애플리케이션 기동을 막지 않는다. 개발 배포 workflow에서만 두 값을 설정하고 운영 배포에는 전달하지 않는다. `debug`에는 사용자 식별자, 참가자 상태, 메시지 본문 또는 저장소 세부 정보를 포함하지 않는다.

## HTTP 메시지 복구

### 기존 과거 이력 조회

- HTTP `GET /api/chat-rooms/{chatRoomId}/messages`는 과거 이력 조회다. 재연결 누락 복구는 아래 sync 경로를 사용한다.
- 페이지 크기는 20이며 정렬 키는 `(createdAt, chatMessageId)`다.
- 최초 조회는 최신 메시지 20개를 선택하고 화면 표시를 위해 오래된 순서로 반환한다. 다음 페이지는 더 오래된 메시지를 반환한다.
- `nextCursor`, `hasNext`를 제공한다.
- 퇴장한 참가자의 조회 요청은 HTTP 403과 `CHAT_ALREADY_LEFT`를 반환한다. Frontend는 이 응답을 받으면 화면의 기존 메시지 목록을 제거하고 `이미 퇴장한 채팅방입니다.` 안내를 표시한다.
- 채팅방 참가자가 아닌 사용자의 조회 요청은 기존 정책대로 HTTP 404 `CHAT_ROOM_NOT_FOUND`를 반환한다.
- 퇴장한 참가자는 과거 메시지를 조회할 수 없지만 메시지 행은 퇴장 즉시 DB에서 삭제하지 않는다.

### #330 DB 누락 복구

- `GET /api/chat-rooms/{chatRoomId}/messages/sync`는 Bearer 인증과 기존 방 접근·입장·미퇴장·만료 검사를 적용하고 `Cache-Control: no-store`로 응답한다.
- after/through 둘 다 생략: `messages=[]`, 방의 커밋 MAX ID 또는 0을 `throughMessageId`·`nextAfterMessageId`에 반환하고 `hasNext=false`로 최초 기준점을 제공한다.
- `afterMessageId`만 지정: 해당 ID 이후부터 방의 커밋 MAX까지를 고정 범위로 정한다. `after < ID <= through`의 메시지를 ID 오름차순으로 최대 20개 반환한다.
- 다음 페이지에는 응답 `nextAfterMessageId`를 after로, 처음 응답의 `throughMessageId`를 그대로 전달한다. 마지막 페이지에서는 `nextAfterMessageId=throughMessageId`다.
- 응답은 `{message,data}`이며 data는 `{messages,throughMessageId,nextAfterMessageId,hasNext}`다. messages의 필드는 기존 history Message와 같다(`chatMessageId`, `clientMessageId`, `userId`, `nickname`, `content`, `createdAt`, `voteChoice`).
- after 없는 through, 음수, after > through, through > 방 MAX는 HTTP 400 `INVALID_CURSOR`다. 숫자 형식·경로 검증 오류는 기존 입력 오류 계약을 따른다.
- 최초 진입은 enter → DB 기준점 → 기존 history/detail → 기준점 이후 sync 순서다. 재연결에도 마지막 DB 확인 완료 지점을 유지하며 고정 상한까지 모두 읽고 상세 조회가 성공한 뒤 지점을 갱신한다.
- live 최고 ID는 중간 메시지 수신의 근거가 아니므로 완료 지점에 사용하지 않는다. ID는 방 안에서 연속이지 않으며 같은 방 INSERT 전 참가자 잠금 조건을 유지한다.
- DB 메시지 ID 및 사용자별 clientMessageId로 live·복구·과거 이력을 병합한다. 본인 메시지의 DB 저장 확인도 live ACK와 같은 전송 대기·retry 정리를 거친다. 별도 STOMP ACK destination을 추가하지 않는다.
- visible/online 화면은 정상 약 15초+jitter 주기로 DB를 대조하고 실패 시 간격을 늘린다. 방·세션 변경 시 이전 요청을 취소한다. 이는 복구 시도 주기이며 최대 복구 시간 SLA가 아니다.

## 전달 준비·장애·스케일인 (#330)

- WebSocket 연결은 각 태스크에 남는다. 모든 CHAT 태스크는 같은 writer DB와 Redis, 동일 환경 채널을 사용해야 한다. Redis 채널은 `mulo:chat:<environment>:delivery:v1`이다.
- ECS는 `CHAT_DELIVERY_ENABLED=true`가 필수다. Redis host/port/TLS/ACL 자격 증명과 `CHAT_DELIVERY_ENVIRONMENT`는 배포 설정에서 주입하고 자격 증명은 Secret으로 관리한다. 로컬의 비활성 모드는 기존 개발 동작을 유지한다.
- 실제 구독 ACK와 발행 경로 5초 안정화 후 새 handshake/CONNECT를 허용한다. 구독 단절·처리 손실 시 소켓을 1012로 종료하고 재연결·DB 복구를 유도한다.
- 종료 시 DRAINING으로 전환해 새 연결·SEND·HTTP 쓰기를 제한하고 소켓을 정리한다. 이미 진입한 SEND는 최대 20초, 전달 큐는 최대 10초 범위에서 기다린 후 Redis 자원을 정리한다. 읽기 요청과 DB 복구 GET은 차단하지 않는다.
- `/health`는 공유 Redis 장애만으로 실패시키지 않는다. 별도 전달 ready 지표와 실패 카운터로 감시한다.
- 최초 구형 로컬 전달/신형 Pub/Sub 전환은 점검 창에서 CHAT 버전을 맞춘 뒤 새 API에 의존하는 FE를 배포한다. 실제 ECS/ALB 종료 시간·Redis TLS/ACL·목표 동접 용량은 스테이징 검증 대상이며 이 명세 작성으로 AWS 설정이 적용되지는 않는다.
- DB 커밋 후 발행 전 종료, 구독 단절로 놓친 DB 저장 메시지는 조회로 복구한다. Pub/Sub 자체 재전송·Outbox·exactly-once·미커밋 요청 자동 재전송은 제공하지 않는다. 이력·권한이 유지되고 조회가 성공해야 화면에 반영된다.
- 방 이벤트 전체의 영속 재생 계약은 없다. 방 상세 재조회로 현재 상태를 대조하며, 과거 이벤트 이력 보장이 필요하면 별도 설계가 필요하다.
