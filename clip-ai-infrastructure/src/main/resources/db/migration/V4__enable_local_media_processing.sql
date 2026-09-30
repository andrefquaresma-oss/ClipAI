ALTER TABLE media_assets
    ALTER COLUMN source_url DROP NOT NULL,
    ADD COLUMN failure_reason TEXT;

ALTER TABLE media_assets
    DROP CONSTRAINT ck_media_assets_status,
    ADD CONSTRAINT ck_media_assets_status CHECK
        (status IN ('PENDING', 'DOWNLOADING', 'READY', 'STORED', 'PROCESSING',
                    'TRANSCRIBING', 'COMPLETED', 'FAILED'));

ALTER TABLE transcripts
    ADD COLUMN failure_reason TEXT;
