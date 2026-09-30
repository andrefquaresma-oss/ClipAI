CREATE TABLE transcripts
(
    id             UUID        PRIMARY KEY,
    media_asset_id UUID        NOT NULL,
    language       VARCHAR(35) NOT NULL,
    status         VARCHAR(20) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_transcripts_media_asset UNIQUE (media_asset_id),
    CONSTRAINT fk_transcripts_media_asset FOREIGN KEY (media_asset_id)
        REFERENCES media_assets (id) ON DELETE CASCADE,
    CONSTRAINT ck_transcripts_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX ix_transcripts_status ON transcripts (status);
