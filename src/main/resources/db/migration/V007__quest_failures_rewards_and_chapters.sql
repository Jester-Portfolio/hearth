-- 기존 ID·완료·인벤토리는 보존하고 새 개인 퀘스트 경계만 확장합니다.
ALTER TABLE hearth_progress.personal_quest DROP CONSTRAINT personal_quest_phase_check;
ALTER TABLE hearth_progress.personal_quest ADD CONSTRAINT personal_quest_phase_check CHECK(phase IN ('ACTIVE','REPORT_READY','COMPLETED','FAILED','SKIPPED'));
ALTER TABLE hearth_progress.personal_quest ADD COLUMN deadline_millis bigint NOT NULL DEFAULT 0 CHECK(deadline_millis>=0);
ALTER TABLE hearth_progress.quest_notification DROP CONSTRAINT quest_notification_kind_check;
ALTER TABLE hearth_progress.quest_notification ADD CONSTRAINT quest_notification_kind_check CHECK(kind IN ('STARTED','OBJECTIVE_UPDATED','REPORT_READY','COMPLETED','FAILED','RESTARTED'));
CREATE TABLE hearth_progress.quest_reward (
 story_run_id uuid NOT NULL, player_uuid uuid NOT NULL, quest_id text NOT NULL,
 reward_hash text NOT NULL, status text NOT NULL CHECK(status IN ('PENDING','GRANTED','POLICY_PENDING')),
 inventory_revision bigint, granted_at timestamptz,
 PRIMARY KEY(story_run_id,player_uuid,quest_id),
 FOREIGN KEY(story_run_id,player_uuid,quest_id) REFERENCES hearth_progress.personal_quest(story_run_id,player_uuid,quest_id),
 CHECK((status='GRANTED' AND inventory_revision>=0 AND granted_at IS NOT NULL) OR (status<>'GRANTED' AND inventory_revision IS NULL AND granted_at IS NULL))
);
CREATE TABLE hearth_progress.chapter_skip (
 story_run_id uuid NOT NULL, player_uuid uuid NOT NULL, chapter_id text NOT NULL CHECK(chapter_id ~ '^[a-z][a-z0-9_]{0,63}$'),
 content_hash text NOT NULL, revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0), skipped_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(story_run_id,player_uuid,chapter_id),
 FOREIGN KEY(story_run_id,player_uuid) REFERENCES hearth_progress.player_progress(story_run_id,player_uuid)
);
