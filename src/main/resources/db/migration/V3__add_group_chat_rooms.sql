ALTER TABLE chat_rooms
    MODIFY COLUMN match_id BIGINT NULL,
    ADD COLUMN room_type VARCHAR(16) NOT NULL DEFAULT 'DIRECT',
    ADD COLUMN group_date DATE NULL,
    ADD COLUMN vote_question_id BIGINT NULL,
    ADD COLUMN room_sequence INT NULL,
    ADD COLUMN capacity SMALLINT NOT NULL DEFAULT 2,
    ADD COLUMN admitted_participant_count SMALLINT NOT NULL DEFAULT 0;

UPDATE chat_rooms room
LEFT JOIN (
    SELECT chat_room_id, COUNT(*) AS participant_count
    FROM chat_room_participants
    GROUP BY chat_room_id
) participants ON participants.chat_room_id = room.chat_room_id
SET room.admitted_participant_count = COALESCE(participants.participant_count, 0);

ALTER TABLE chat_rooms
    ADD CONSTRAINT uk_chat_rooms_group_sequence
        UNIQUE (group_date, vote_question_id, room_sequence),
    ADD CONSTRAINT ck_chat_rooms_type_metadata
        CHECK (
            (room_type = 'DIRECT' AND match_id IS NOT NULL AND group_date IS NULL
                AND vote_question_id IS NULL AND room_sequence IS NULL AND capacity = 2)
            OR
            (room_type = 'GROUP' AND match_id IS NULL AND group_date IS NOT NULL
                AND vote_question_id IS NOT NULL AND room_sequence IS NOT NULL
                AND room_sequence > 0 AND capacity = 30)
        ),
    ADD CONSTRAINT ck_chat_rooms_admitted_count CHECK (
        admitted_participant_count >= 0 AND admitted_participant_count <= capacity
    );

ALTER TABLE chat_room_participants
    ADD COLUMN group_date DATE NULL,
    ADD COLUMN vote_choice VARCHAR(1) NULL,
    ADD CONSTRAINT uk_chat_room_participants_group_date_user UNIQUE (group_date, user_id),
    ADD CONSTRAINT ck_chat_room_participants_vote_choice
        CHECK (
            (group_date IS NULL AND vote_choice IS NULL)
            OR (group_date IS NOT NULL AND vote_choice IS NOT NULL AND vote_choice IN ('A', 'B'))
        );

CREATE TABLE chat_room_group_allocators (
    chat_room_group_allocator_id BIGINT NOT NULL AUTO_INCREMENT,
    group_date DATE NOT NULL,
    vote_question_id BIGINT NOT NULL,
    last_room_sequence INT NOT NULL DEFAULT 0,
    CONSTRAINT pk_chat_room_group_allocators PRIMARY KEY (chat_room_group_allocator_id),
    CONSTRAINT uk_chat_room_group_allocators_date_question UNIQUE (group_date, vote_question_id)
);
