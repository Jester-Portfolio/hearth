-- 기존 E001~E003과 계정/시장 행을 보존합니다. 두 계정과 영수증은 한 트랜잭션입니다.
ALTER TABLE hearth_economy.version DROP CONSTRAINT version_version_check;
ALTER TABLE hearth_economy.version ADD CONSTRAINT version_version_check CHECK(version BETWEEN 1 AND 4);
CREATE TABLE hearth_economy.peer_trade(
 realm_id uuid NOT NULL,request_id uuid NOT NULL,fingerprint text NOT NULL CHECK(fingerprint ~ '^[a-f0-9]{64}$'),
 first_player uuid NOT NULL,second_player uuid NOT NULL CHECK(second_player<>first_player),
 first_offer text NOT NULL,second_offer text NOT NULL,
 first_revision bigint NOT NULL CHECK(first_revision>0),second_revision bigint NOT NULL CHECK(second_revision>0),
 PRIMARY KEY(realm_id,request_id),
 FOREIGN KEY(realm_id,first_player) REFERENCES hearth_economy.account,
 FOREIGN KEY(realm_id,second_player) REFERENCES hearth_economy.account
);
CREATE INDEX economy_peer_first ON hearth_economy.peer_trade(realm_id,first_player,first_revision DESC);
CREATE INDEX economy_peer_second ON hearth_economy.peer_trade(realm_id,second_player,second_revision DESC);
