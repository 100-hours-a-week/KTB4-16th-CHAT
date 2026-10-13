CREATE TABLE chat_rooms (
    chat_room_id BIGINT NOT NULL AUTO_INCREMENT,
    match_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_chat_rooms PRIMARY KEY (chat_room_id),
    CONSTRAINT uk_chat_rooms_match UNIQUE (match_id)
);

CREATE TABLE chat_room_participants (
    chat_room_participant_id BIGINT NOT NULL AUTO_INCREMENT,
    chat_room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    nickname VARCHAR(10) NOT NULL,
    joined_at DATETIME NULL,
    left_at DATETIME NULL,
    CONSTRAINT pk_chat_room_participants PRIMARY KEY (chat_room_participant_id),
    CONSTRAINT uk_chat_room_participants_room_user UNIQUE (chat_room_id, user_id),
    CONSTRAINT fk_chat_room_participants_room
        FOREIGN KEY (chat_room_id)
        REFERENCES chat_rooms (chat_room_id)
        ON DELETE CASCADE
);

CREATE TABLE chat_messages (
    chat_message_id BIGINT NOT NULL AUTO_INCREMENT,
    chat_room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    client_message_id CHAR(36) NOT NULL,
    content VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_chat_messages PRIMARY KEY (chat_message_id),
    CONSTRAINT uk_chat_messages_user_client_message UNIQUE (user_id, client_message_id),
    CONSTRAINT fk_chat_messages_room
        FOREIGN KEY (chat_room_id)
        REFERENCES chat_rooms (chat_room_id)
        ON DELETE CASCADE,
    INDEX idx_chat_messages_room_created_id (chat_room_id, created_at, chat_message_id)
);
