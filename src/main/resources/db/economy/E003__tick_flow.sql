-- 최근 확정 Tick의 집계만 보존합니다. 과거 실적이나 거래 원장을 새로 만들지 않습니다.
ALTER TABLE hearth_economy.version DROP CONSTRAINT version_version_check;
ALTER TABLE hearth_economy.version ADD CONSTRAINT version_version_check CHECK(version BETWEEN 1 AND 3);
ALTER TABLE hearth_economy.realm ADD COLUMN flow_tick bigint CHECK(flow_tick>0 AND flow_tick=tick);
CREATE TABLE hearth_economy.tick_flow(
    realm_id uuid NOT NULL,
    region_id text NOT NULL,
    good_id text NOT NULL,
    tick bigint NOT NULL CHECK(tick>0),
    before_stock bigint NOT NULL CHECK(before_stock BETWEEN 0 AND 1000000000000),
    produced bigint NOT NULL CHECK(produced>=0),
    inputs bigint NOT NULL CHECK(inputs>=0),
    consumed bigint NOT NULL CHECK(consumed>=0),
    shortage bigint NOT NULL CHECK(shortage>=0),
    arrived bigint NOT NULL CHECK(arrived>=0),
    departed bigint NOT NULL CHECK(departed>=0),
    after_stock bigint NOT NULL CHECK(after_stock BETWEEN 0 AND 1000000000000),
    PRIMARY KEY(realm_id,region_id,good_id),
    FOREIGN KEY(realm_id,region_id,good_id) REFERENCES hearth_economy.market,
    CHECK(before_stock+produced+arrived-inputs-consumed-departed=after_stock)
);
