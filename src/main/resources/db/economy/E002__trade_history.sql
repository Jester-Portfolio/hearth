-- E001 파일·기존 행을 보존하고 마이그레이션 범위와 조회 인덱스만 확장합니다.
ALTER TABLE hearth_economy.version DROP CONSTRAINT version_version_check;
ALTER TABLE hearth_economy.version ADD CONSTRAINT version_version_check CHECK(version BETWEEN 1 AND 2);
CREATE INDEX economy_trade_owner_revision ON hearth_economy.trade(realm_id,player_uuid,inventory_revision DESC);
