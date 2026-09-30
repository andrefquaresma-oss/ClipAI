ALTER TABLE media_assets
    ADD COLUMN match_part VARCHAR(20) NOT NULL DEFAULT 'OTHER',
    ADD CONSTRAINT ck_media_assets_match_part
        CHECK (match_part IN ('FIRST_HALF', 'SECOND_HALF', 'FULL_MATCH', 'OTHER'));
