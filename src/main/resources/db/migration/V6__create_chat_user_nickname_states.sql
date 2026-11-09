-- 첫 버전 메시지로 생성하는 사용자별 최신 닉네임 상태다.
CREATE TABLE chat_user_nickname_states (
    user_id BIGINT NOT NULL,
    nickname VARCHAR(10) NOT NULL,
    nickname_version BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_chat_user_nickname_states PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
