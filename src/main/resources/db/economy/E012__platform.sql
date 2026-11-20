ALTER TABLE hearth_economy.version DROP CONSTRAINT version_version_check;
ALTER TABLE hearth_economy.version ADD CONSTRAINT version_version_check CHECK(version BETWEEN 1 AND 12);
CREATE TABLE hearth_economy.platform_preference (
 realm_id uuid NOT NULL,player_uuid uuid NOT NULL,enabled boolean NOT NULL,goods text[] NOT NULL,
 revision bigint NOT NULL CHECK(revision>=0),PRIMARY KEY(realm_id,player_uuid),
 FOREIGN KEY(realm_id,player_uuid) REFERENCES hearth_economy.account
);
CREATE TABLE hearth_economy.resource_reset (
 realm_id uuid NOT NULL REFERENCES hearth_economy.native_binding,request_id uuid NOT NULL,
 role text NOT NULL CHECK(role IN ('wilderness','nether','novice','end')),
 generation bigint NOT NULL CHECK(generation>0),old_uuid uuid NOT NULL,new_key text NOT NULL,
 seed bigint NOT NULL,terrain text NOT NULL CHECK(terrain IN ('NORMAL','FLAT')),
 new_uuid uuid,entry_x double precision,entry_y double precision,entry_z double precision,
 phase text NOT NULL CHECK(phase IN ('PREPARING','READY','ACTIVE','RETIRED','ABORTED')),
 PRIMARY KEY(realm_id,request_id),UNIQUE(new_key),
 CHECK(phase IN ('PREPARING','ABORTED') OR (new_uuid IS NOT NULL AND entry_x IS NOT NULL AND entry_y IS NOT NULL AND entry_z IS NOT NULL))
);
CREATE UNIQUE INDEX resource_one_pending ON hearth_economy.resource_reset(realm_id,role) WHERE phase IN ('PREPARING','READY');
CREATE UNIQUE INDEX resource_one_active ON hearth_economy.resource_reset(realm_id,role) WHERE phase='ACTIVE';
CREATE TABLE hearth_economy.platform_config_change (
 realm_id uuid NOT NULL REFERENCES hearth_economy.native_binding,request_id uuid NOT NULL,
 name text NOT NULL,before_hash text NOT NULL CHECK(length(before_hash)=64),after_hash text NOT NULL CHECK(length(after_hash)=64),
 state text NOT NULL CHECK(state IN ('PREPARED','COMMITTED','ROLLED_BACK')),created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(realm_id,request_id)
);
