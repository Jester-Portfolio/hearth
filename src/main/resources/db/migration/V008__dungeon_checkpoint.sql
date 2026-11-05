CREATE TABLE hearth_progress.dungeon_checkpoint (
 story_run_id uuid NOT NULL REFERENCES hearth_progress.story_run(story_run_id),
 dungeon_id text NOT NULL CHECK (dungeon_id ~ '^[a-z][a-z0-9_]{0,63}$'),
 quest_id text NOT NULL CHECK (quest_id ~ '^[a-z][a-z0-9_]{0,63}$'),
 checkpoint_id text NOT NULL CHECK (checkpoint_id ~ '^[a-z][a-z0-9_]{0,63}$'),
 request_id uuid NOT NULL,
 complete boolean NOT NULL DEFAULT false,
 PRIMARY KEY(story_run_id,dungeon_id,quest_id,checkpoint_id)
);
