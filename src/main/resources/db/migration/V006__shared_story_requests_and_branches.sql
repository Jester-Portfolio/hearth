CREATE TABLE hearth_progress.community_story_request (
 story_run_id uuid NOT NULL REFERENCES hearth_progress.story_run(story_run_id), request_id uuid NOT NULL,
 fingerprint text NOT NULL, resulting_revision bigint NOT NULL CHECK(resulting_revision>=0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(story_run_id,request_id)
);
CREATE TABLE hearth_progress.community_story_completion (
 story_run_id uuid NOT NULL, stage_id text NOT NULL CHECK(stage_id ~ '^[a-z][a-z0-9_]{0,63}$'), request_id uuid NOT NULL,
 completed_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(story_run_id,stage_id),
 FOREIGN KEY(story_run_id,request_id) REFERENCES hearth_progress.community_story_request(story_run_id,request_id)
);
CREATE TABLE hearth_progress.community_story_branch (
 story_run_id uuid NOT NULL, stage_id text NOT NULL CHECK(stage_id ~ '^[a-z][a-z0-9_]{0,63}$'), choice_id text NOT NULL CHECK(choice_id ~ '^[a-z][a-z0-9_]{0,63}$'),
 choice_label text NOT NULL, request_id uuid NOT NULL, PRIMARY KEY(story_run_id,stage_id), UNIQUE(story_run_id,request_id),
 FOREIGN KEY(story_run_id,request_id) REFERENCES hearth_progress.community_story_request(story_run_id,request_id)
);
