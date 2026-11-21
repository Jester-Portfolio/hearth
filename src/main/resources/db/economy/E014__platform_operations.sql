ALTER TABLE hearth_economy.version DROP CONSTRAINT version_version_check;
ALTER TABLE hearth_economy.version ADD CONSTRAINT version_version_check CHECK(version BETWEEN 1 AND 14);
ALTER TABLE hearth_economy.platform_config_change ADD COLUMN archived boolean NOT NULL DEFAULT false;
ALTER TABLE hearth_economy.platform_job ADD COLUMN archived boolean NOT NULL DEFAULT false;
ALTER TABLE hearth_economy.platform_config_change ADD CONSTRAINT config_archive_terminal CHECK(NOT archived OR state IN ('COMMITTED','ROLLED_BACK'));
ALTER TABLE hearth_economy.platform_job ADD CONSTRAINT job_archive_terminal CHECK(NOT archived OR state IN ('SUCCEEDED','CANCELLED'));
CREATE INDEX config_hot_history ON hearth_economy.platform_config_change(realm_id,created_at DESC,request_id) WHERE NOT archived;
CREATE INDEX job_hot_history ON hearth_economy.platform_job(realm_id,created_at DESC,request_id) WHERE NOT archived;
CREATE TABLE hearth_economy.resource_archive (
 realm_id uuid NOT NULL REFERENCES hearth_economy.native_binding, request_id uuid NOT NULL,
 world_uuid uuid NOT NULL, world_key text NOT NULL, seal text NOT NULL CHECK(seal ~ '^[a-f0-9]{64}$'),
 state text NOT NULL CHECK(state IN ('PREPARED','STORED','RESTORING','RESTORED')),
 revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0), PRIMARY KEY(realm_id,request_id),
 FOREIGN KEY(realm_id,request_id) REFERENCES hearth_economy.resource_reset(realm_id,request_id)
);
