-- native playerdata는 평상시 정본이며 account.payload는 거래 시점의 그림자입니다.
ALTER TABLE hearth_economy.version DROP CONSTRAINT version_version_check;
ALTER TABLE hearth_economy.version ADD CONSTRAINT version_version_check CHECK(version BETWEEN 1 AND 6);
CREATE TABLE hearth_economy.native_binding (
 realm_id uuid PRIMARY KEY REFERENCES hearth_economy.realm(realm_id),
 group_id uuid NOT NULL UNIQUE,
 worlds_hash text NOT NULL CHECK(length(worlds_hash)=64)
);
