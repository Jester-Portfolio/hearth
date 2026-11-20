ALTER TABLE hearth_economy.version DROP CONSTRAINT version_version_check;
ALTER TABLE hearth_economy.version ADD CONSTRAINT version_version_check CHECK(version BETWEEN 1 AND 13);
CREATE TABLE hearth_economy.platform_schedule (
 realm_id uuid NOT NULL REFERENCES hearth_economy.native_binding,
 target text NOT NULL CHECK(target IN ('wilderness','nether','novice','end')),
 interval_ticks bigint NOT NULL CHECK(interval_ticks BETWEEN 0 AND 100000000),
 next_tick bigint NOT NULL CHECK(next_tick>=0),revision bigint NOT NULL CHECK(revision>=0),
 PRIMARY KEY(realm_id,target)
);
CREATE TABLE hearth_economy.platform_job (
 realm_id uuid NOT NULL REFERENCES hearth_economy.native_binding,request_id uuid NOT NULL,
 version integer NOT NULL CHECK(version=1),kind text NOT NULL,target text NOT NULL,
 arguments text NOT NULL CHECK(length(arguments)<=4096),started_tick bigint NOT NULL CHECK(started_tick>=0),
 scheduled boolean NOT NULL,state text NOT NULL CHECK(state IN ('RUNNING','HOLD','SUCCEEDED','CANCELLED')),
 revision bigint NOT NULL CHECK(revision>=0),message text NOT NULL CHECK(length(message)<=160),
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(realm_id,request_id)
);
CREATE UNIQUE INDEX platform_one_active_job ON hearth_economy.platform_job(realm_id) WHERE state IN ('RUNNING','HOLD');
