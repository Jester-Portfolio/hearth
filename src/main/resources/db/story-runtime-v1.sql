CREATE TABLE hearth_runtime.story_binding (
    story_run_id uuid PRIMARY KEY REFERENCES hearth_progress.story_run(story_run_id),
    world_key text NOT NULL UNIQUE,
    world_uuid uuid NOT NULL UNIQUE,
    template_version text NOT NULL,
    template_hash text NOT NULL CHECK (template_hash ~ '^[a-f0-9]{64}$'),
    content_hash text NOT NULL CHECK (content_hash ~ '^[a-f0-9]{64}$')
);
CREATE TABLE hearth_runtime.story_member (
    player_uuid uuid PRIMARY KEY,
    story_run_id uuid NOT NULL REFERENCES hearth_runtime.story_binding(story_run_id),
    FOREIGN KEY(story_run_id,player_uuid) REFERENCES hearth_progress.player_progress(story_run_id,player_uuid)
);
