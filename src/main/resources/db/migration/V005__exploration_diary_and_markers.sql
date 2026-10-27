CREATE TABLE hearth_progress.exploration_place (
 story_run_id uuid NOT NULL, player_uuid uuid NOT NULL, place_id text NOT NULL CHECK(place_id ~ '^[a-z][a-z0-9_]{0,63}$'),
 visited_at timestamptz, discovered_at timestamptz, hub_unlocked boolean NOT NULL DEFAULT false,
 revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0), PRIMARY KEY(story_run_id,player_uuid,place_id),
 FOREIGN KEY(story_run_id,player_uuid) REFERENCES hearth_progress.player_progress(story_run_id,player_uuid),
 CHECK(NOT hub_unlocked OR visited_at IS NOT NULL)
);
CREATE TABLE hearth_progress.place_notification (
 story_run_id uuid NOT NULL, player_uuid uuid NOT NULL, place_id text NOT NULL CHECK(place_id ~ '^[a-z][a-z0-9_]{0,63}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), is_read boolean NOT NULL DEFAULT false,
 PRIMARY KEY(story_run_id,player_uuid,place_id),
 FOREIGN KEY(story_run_id,player_uuid,place_id) REFERENCES hearth_progress.exploration_place(story_run_id,player_uuid,place_id)
);
ALTER TABLE hearth_progress.quest_preferences ADD COLUMN minimap_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE hearth_progress.quest_preferences ADD COLUMN quest_marker boolean NOT NULL DEFAULT true;
ALTER TABLE hearth_progress.quest_preferences ADD COLUMN npc_marker boolean NOT NULL DEFAULT true;
ALTER TABLE hearth_progress.quest_preferences ADD COLUMN player_marker boolean NOT NULL DEFAULT true;
ALTER TABLE hearth_progress.quest_preferences ADD COLUMN place_marker boolean NOT NULL DEFAULT true;
CREATE TABLE hearth_progress.diary_personal_fact (
 story_run_id uuid NOT NULL, player_uuid uuid NOT NULL, entry_id text NOT NULL,
 category text NOT NULL CHECK(category IN ('STORY','PERSON','EXPLORATION')), title text NOT NULL, summary text NOT NULL, detail text NOT NULL,
 version integer NOT NULL CHECK(version>0), first_seen timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(story_run_id,player_uuid,entry_id), FOREIGN KEY(story_run_id,player_uuid) REFERENCES hearth_progress.player_progress(story_run_id,player_uuid)
);
CREATE TABLE hearth_progress.diary_personal_event (
 story_run_id uuid NOT NULL, player_uuid uuid NOT NULL, entry_id text NOT NULL, version integer NOT NULL,
 category text NOT NULL, title text NOT NULL, summary text NOT NULL, detail text NOT NULL, occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(story_run_id,player_uuid,entry_id,version), FOREIGN KEY(story_run_id,player_uuid,entry_id) REFERENCES hearth_progress.diary_personal_fact(story_run_id,player_uuid,entry_id)
);
CREATE TABLE hearth_progress.diary_shared_fact (
 story_run_id uuid NOT NULL REFERENCES hearth_progress.story_run(story_run_id), entry_id text NOT NULL,
 category text NOT NULL CHECK(category IN ('STORY','PERSON','EXPLORATION')), title text NOT NULL, summary text NOT NULL, detail text NOT NULL,
 version integer NOT NULL CHECK(version>0), first_seen timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(story_run_id,entry_id)
);
CREATE TABLE hearth_progress.diary_shared_event (
 story_run_id uuid NOT NULL, entry_id text NOT NULL, version integer NOT NULL,
 category text NOT NULL, title text NOT NULL, summary text NOT NULL, detail text NOT NULL, occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(story_run_id,entry_id,version), FOREIGN KEY(story_run_id,entry_id) REFERENCES hearth_progress.diary_shared_fact(story_run_id,entry_id)
);
CREATE TABLE hearth_progress.community_story (
 story_run_id uuid PRIMARY KEY REFERENCES hearth_progress.story_run(story_run_id), stage_id text, stage_title text, exploration_open boolean,
 revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0), CHECK((stage_id IS NULL)=(stage_title IS NULL))
);
