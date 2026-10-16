# MULO Chat Server STOMP 명세 v2.0

## 범위와 소유권

- 이 문서는 Chat Server 소유의 WebSocket + STOMP 실시간 메시지 계약이다.
- HTTP 채팅 API와 Backend 연동 API는 `specs/openapi/MULO_OpenAPI_v2.7.yaml`을 기준으로 한다.
- Chat Server 전용 로그인은 없다. 사용자 인증은 Backend Access Token 검증 결과를 사용한다.
- 채팅방은 매칭된 두 명만 참여하는 1:1 구조다.
- 메시지는 Chat Server MySQL에 저장한다. DB COMMIT 성공 후에만 실시간으로 전달한다. V2에서는 Redis Pub/Sub을 사용하지 않는다.

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
- DB COMMIT 성공 후 발신자와 상대방의 사용자별 큐로 결과를 보낸다.
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
- 퇴장한 사용자는 이후 채팅방 메시지·이벤트를 구독할 수 없고 메시지를 보낼 수 없다. Frontend는 자신의 STOMP 구독도 해제한다.
- 수신 권한은 서버가 매 이벤트 전달 시 확인하며 Frontend의 UNSUBSCRIBE에만 의존하지 않는다.

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
| CONNECT 인증 실패 | `UNAUTHORIZED` | CONNECT 거부 |
| 지원하지 않는 STOMP 명령 | `COMMAND_NOT_ALLOWED` | 명령 거부 |
| 허용되지 않은 SUBSCRIBE | `STOMP_DESTINATION_FORBIDDEN` | 구독 거부 |
| 허용되지 않은 SEND Destination | `STOMP_DESTINATION_FORBIDDEN` | SEND 거부 |
| 참가자/입장/퇴장 상태로 SEND 불가 | `CHAT_SEND_FORBIDDEN` | 메시지를 저장하거나 전달하지 않음 |
| 채팅방 만료 | `CHAT_ROOM_EXPIRED` | 메시지를 저장하거나 전달하지 않음 |
| 같은 ID를 다른 방 또는 내용에 재사용 | `CLIENT_MESSAGE_ID_CONFLICT` | 기존 메시지를 변경하지 않음 |
| 메시지 500자 초과 | `MESSAGE_TOO_LONG` | 메시지를 저장하거나 전달하지 않음 |
| 필수 필드 누락 또는 요청 본문 형식 오류 | `INVALID_INPUT_VALUE` | 메시지를 저장하거나 전달하지 않음 |

STOMP 오류는 HTTP 상태 코드가 아니다. 실패 메시지에 별도 ACK destination을 추가하지 않는다.

## HTTP 메시지 복구

- 재연결 후 놓친 메시지는 HTTP `GET /api/chat-rooms/{chatRoomId}/messages`로 복구한다.
- 페이지 크기는 20이며 정렬 키는 `(createdAt, chatMessageId)`다.
- 최초 조회는 최신 메시지 20개를 선택하고 화면 표시를 위해 오래된 순서로 반환한다. 다음 페이지는 더 오래된 메시지를 반환한다.
- `nextCursor`, `hasNext`를 제공한다.
- 퇴장한 참가자의 조회 요청은 HTTP 403과 `CHAT_ALREADY_LEFT`를 반환한다. Frontend는 이 응답을 받으면 화면의 기존 메시지 목록을 제거하고 `이미 퇴장한 채팅방입니다.` 안내를 표시한다.
- 채팅방 참가자가 아닌 사용자의 조회 요청은 기존 정책대로 HTTP 404 `CHAT_ROOM_NOT_FOUND`를 반환한다.
- 퇴장한 참가자는 과거 메시지를 조회할 수 없지만 메시지 행은 퇴장 즉시 DB에서 삭제하지 않는다.
