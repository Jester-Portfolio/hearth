ALTER TABLE hearth_economy.version DROP CONSTRAINT version_version_check;
ALTER TABLE hearth_economy.version ADD CONSTRAINT version_version_check CHECK(version BETWEEN 1 AND 7);
CREATE TABLE hearth_economy.market_controller (
 realm_id uuid PRIMARY KEY REFERENCES hearth_economy.native_binding(realm_id),
 policy_hash text NOT NULL CHECK(length(policy_hash)=64),
 tick bigint NOT NULL CHECK(tick>=0),
 mode text NOT NULL CHECK(mode IN ('BALANCED','RESTOCK','CONSERVE')),
 since_tick bigint NOT NULL CHECK(since_tick>=0 AND since_tick<=tick),
 candidate text NOT NULL CHECK(candidate IN ('BALANCED','RESTOCK','CONSERVE')),
 votes integer NOT NULL CHECK(votes BETWEEN 0 AND 8),
 revision bigint NOT NULL CHECK(revision>=0)
);
CREATE TABLE hearth_economy.market_mode_change (
 realm_id uuid NOT NULL REFERENCES hearth_economy.market_controller(realm_id),
 tick bigint NOT NULL CHECK(tick>=0),
 before_mode text NOT NULL CHECK(before_mode IN ('BALANCED','RESTOCK','CONSERVE')),
 after_mode text NOT NULL CHECK(after_mode IN ('BALANCED','RESTOCK','CONSERVE')),
 source text NOT NULL CHECK(source IN ('INITIAL','RULE','LLM')),
 PRIMARY KEY(realm_id,tick)
);
