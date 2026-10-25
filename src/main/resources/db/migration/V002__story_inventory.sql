-- 스토리 인벤토리만 추가합니다. V001·경제 데이터·일반 상자를 변경하지 않습니다.
CREATE TABLE hearth_progress.story_inventory (
    story_run_id uuid NOT NULL,
    player_uuid uuid NOT NULL,
    payload bytea NOT NULL CHECK (octet_length(payload) BETWEEN 208 AND 1048576),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY (story_run_id,player_uuid),
    FOREIGN KEY (story_run_id,player_uuid) REFERENCES hearth_progress.player_progress(story_run_id,player_uuid)
);
