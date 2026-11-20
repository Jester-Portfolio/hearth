ALTER TABLE hearth_economy.version DROP CONSTRAINT version_version_check;
ALTER TABLE hearth_economy.version ADD CONSTRAINT version_version_check CHECK(version BETWEEN 1 AND 11);
CREATE TABLE hearth_economy.enchant_operation (
 realm_id uuid NOT NULL, request_id uuid NOT NULL, player_uuid uuid NOT NULL,
 fingerprint text NOT NULL, action text NOT NULL, enchant_id text NOT NULL,
 before_hash text NOT NULL, after_hash text NOT NULL,
 outcome text NOT NULL, chance double precision NOT NULL CHECK(chance BETWEEN 0 AND 1),
 roll double precision NOT NULL CHECK(roll>=0 AND roll<1), cost bigint NOT NULL CHECK(cost>=0),
 revision bigint NOT NULL CHECK(revision>=0), created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(realm_id,request_id),
 FOREIGN KEY(realm_id,player_uuid) REFERENCES hearth_economy.account
);
CREATE TABLE hearth_economy.enchant_cooldown (
 realm_id uuid NOT NULL, player_uuid uuid NOT NULL, enchant_id text NOT NULL,
 until_millis bigint NOT NULL CHECK(until_millis>0),
 PRIMARY KEY(realm_id,player_uuid,enchant_id),
 FOREIGN KEY(realm_id,player_uuid) REFERENCES hearth_economy.account
);
