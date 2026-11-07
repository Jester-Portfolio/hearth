CREATE TABLE hearth_progress.gathering_instance (
 story_run_id uuid NOT NULL REFERENCES hearth_progress.story_run(story_run_id),
 dungeon_id text NOT NULL CHECK(dungeon_id ~ '^[a-z][a-z0-9_]{0,63}$'),
 world_uuid uuid NOT NULL,
 revision bigint NOT NULL CHECK(revision>=0),
 generation bigint NOT NULL CHECK(generation>=0),
 area_hash text NOT NULL CHECK(length(area_hash)=64),
 template_version text NOT NULL,
 template_hash text NOT NULL CHECK(length(template_hash)=64),
 phase text NOT NULL CHECK(phase IN ('OPEN','RESETTING','ERROR_HOLD')),
 request_id uuid,
 empty_reset boolean NOT NULL,
 next_reset bigint NOT NULL CHECK(next_reset>=0),
 PRIMARY KEY(story_run_id,dungeon_id)
);
