-- 기존 단일 목표는 index 0을 유지합니다. 기존 상태·revision·ID는 변경하지 않습니다.
ALTER TABLE hearth_progress.personal_quest ADD COLUMN objective_index integer NOT NULL DEFAULT 0 CHECK (objective_index >= 0);
CREATE TABLE hearth_progress.quest_tracking (
 story_run_id uuid NOT NULL, player_uuid uuid NOT NULL, quest_id text NOT NULL,
 PRIMARY KEY(story_run_id,player_uuid,quest_id),
 FOREIGN KEY(story_run_id,player_uuid,quest_id) REFERENCES hearth_progress.personal_quest(story_run_id,player_uuid,quest_id)
);
CREATE TABLE hearth_progress.quest_preferences (
 story_run_id uuid NOT NULL, player_uuid uuid NOT NULL, hud_enabled boolean NOT NULL DEFAULT true,
 revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0), PRIMARY KEY(story_run_id,player_uuid),
 FOREIGN KEY(story_run_id,player_uuid) REFERENCES hearth_progress.player_progress(story_run_id,player_uuid)
);
CREATE TABLE hearth_progress.quest_notification (
 story_run_id uuid NOT NULL, player_uuid uuid NOT NULL, quest_id text NOT NULL,
 event_revision bigint NOT NULL CHECK(event_revision>=0), kind text NOT NULL CHECK(kind IN ('STARTED','OBJECTIVE_UPDATED','REPORT_READY','COMPLETED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), is_read boolean NOT NULL DEFAULT false,
 PRIMARY KEY(story_run_id,player_uuid,quest_id,event_revision),
 FOREIGN KEY(story_run_id,player_uuid,quest_id) REFERENCES hearth_progress.personal_quest(story_run_id,player_uuid,quest_id)
);
