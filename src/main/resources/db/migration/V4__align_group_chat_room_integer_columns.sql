ALTER TABLE chat_rooms
    MODIFY COLUMN capacity INT NOT NULL DEFAULT 2,
    MODIFY COLUMN admitted_participant_count INT NOT NULL DEFAULT 0;
