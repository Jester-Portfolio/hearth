CREATE TABLE hearth_progress.personal_feature (
 story_run_id uuid NOT NULL,
 player_uuid uuid NOT NULL,
 feature text NOT NULL CHECK(feature IN ('cutscene','ender','combat_return','device')),
 feature_key text NOT NULL CHECK(feature_key ~ '^[a-z][a-z0-9_]{0,63}$'),
 payload bytea NOT NULL CHECK(octet_length(payload) BETWEEN 1 AND 1048576),
 revision bigint NOT NULL CHECK(revision>=0),
 PRIMARY KEY(story_run_id,player_uuid,feature,feature_key),
 FOREIGN KEY(story_run_id,player_uuid) REFERENCES hearth_progress.player_progress(story_run_id,player_uuid)
);
