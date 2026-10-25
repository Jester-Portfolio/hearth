-- S4-4 최소 진행 저장. 가입/탈퇴/파티장·물리 월드 소유 키를 추가하지 않습니다.
CREATE TABLE hearth_progress.story_run (
    story_run_id uuid PRIMARY KEY,
    state_value text NOT NULL CHECK (octet_length(state_value) <= 4096),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0)
);
CREATE TABLE hearth_progress.player_progress (
    story_run_id uuid NOT NULL REFERENCES hearth_progress.story_run(story_run_id),
    player_uuid uuid NOT NULL,
    state_value text NOT NULL CHECK (octet_length(state_value) <= 4096),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY (story_run_id, player_uuid)
);
