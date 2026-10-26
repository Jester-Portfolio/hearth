-- 공개 시험용 개인 퀘스트. V001/V002·인벤토리 정본과 분리합니다.
CREATE TABLE hearth_progress.personal_quest (
    story_run_id uuid NOT NULL,
    player_uuid uuid NOT NULL,
    quest_id text NOT NULL CHECK (quest_id ~ '^[a-z][a-z0-9_]{0,63}$'),
    phase text NOT NULL CHECK (phase IN ('ACTIVE','REPORT_READY','COMPLETED')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY (story_run_id, player_uuid, quest_id),
    FOREIGN KEY (story_run_id, player_uuid) REFERENCES hearth_progress.player_progress(story_run_id, player_uuid)
);
