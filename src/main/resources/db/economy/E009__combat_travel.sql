ALTER TABLE hearth_economy.version DROP CONSTRAINT version_version_check;
ALTER TABLE hearth_economy.version ADD CONSTRAINT version_version_check CHECK(version BETWEEN 1 AND 9);
CREATE TABLE hearth_economy.combat_tag (
    realm_id uuid NOT NULL REFERENCES hearth_economy.native_binding,
    player_uuid uuid NOT NULL,
    until_millis bigint NOT NULL CHECK (until_millis > 0),
    PRIMARY KEY (realm_id,player_uuid)
);
CREATE INDEX combat_tag_active ON hearth_economy.combat_tag(realm_id,until_millis);
