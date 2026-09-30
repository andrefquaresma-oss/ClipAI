CREATE TABLE media_assets
(
    id                UUID         PRIMARY KEY,
    source            VARCHAR(100) NOT NULL,
    source_url        VARCHAR(2048) NOT NULL,
    external_id       VARCHAR(255),
    title             VARCHAR(500) NOT NULL,
    content_type      VARCHAR(40)  NOT NULL,
    duration_ms       BIGINT,
    local_storage_path VARCHAR(2048),
    status            VARCHAR(20)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_media_assets_content_type CHECK
        (content_type IN ('GENERIC', 'GAMING', 'IRL', 'PODCAST', 'INTERVIEW', 'SPORTS',
                          'POLITICAL_SPEECH', 'NEWS', 'EDUCATIONAL')),
    CONSTRAINT ck_media_assets_status CHECK
        (status IN ('PENDING', 'DOWNLOADING', 'READY', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_media_assets_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
);

CREATE INDEX ix_media_assets_status ON media_assets (status);
CREATE UNIQUE INDEX uq_media_assets_source_external_id
    ON media_assets (source, external_id)
    WHERE external_id IS NOT NULL;
