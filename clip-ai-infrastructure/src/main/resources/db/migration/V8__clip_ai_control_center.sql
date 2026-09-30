ALTER TABLE media_assets
    ADD COLUMN original_filename VARCHAR(500),
    ADD COLUMN competition VARCHAR(255),
    ADD COLUMN home_team VARCHAR(255),
    ADD COLUMN away_team VARCHAR(255),
    ADD COLUMN match_date DATE,
    ADD COLUMN language VARCHAR(35);

ALTER TABLE media_assets
    DROP CONSTRAINT ck_media_assets_status,
    ADD CONSTRAINT ck_media_assets_status CHECK
        (status IN ('PENDING', 'DOWNLOADING', 'READY', 'STORED', 'PROCESSING',
                    'AUDIO_EXTRACTED', 'TRANSCRIBING', 'COMPLETED', 'FAILED'));

ALTER TABLE candidate_events
    DROP CONSTRAINT ck_candidate_events_detection_status,
    ADD CONSTRAINT ck_candidate_events_detection_status CHECK
        (detection_status IN ('DETECTED', 'AI_ANALYZED', 'REJECTED', 'MANUAL'));

ALTER TABLE candidate_reviews
    ADD COLUMN review_note TEXT,
    ADD COLUMN event_type_override VARCHAR(40),
    ADD CONSTRAINT ck_candidate_reviews_event_type_override CHECK
        (event_type_override IS NULL OR event_type_override IN
            ('GOAL', 'BIG_CHANCE', 'SHOT', 'SAVE', 'PENALTY', 'MISSED_PENALTY',
             'RED_CARD', 'YELLOW_CARD', 'VAR', 'FOUL', 'COUNTER_ATTACK', 'ATTACK',
             'NEAR_MISS', 'CELEBRATION', 'CROWD_REACTION', 'COMMENTATOR_REACTION',
             'CONTROVERSIAL_DECISION', 'DRAMATIC_MOMENT', 'UNKNOWN'));

CREATE TABLE ground_truth_match_reviews
(
    media_asset_id UUID        PRIMARY KEY,
    review_status  VARCHAR(20) NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_ground_truth_match_reviews_asset FOREIGN KEY (media_asset_id)
        REFERENCES media_assets (id) ON DELETE CASCADE,
    CONSTRAINT ck_ground_truth_match_reviews_status CHECK
        (review_status IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE TABLE ground_truth_events
(
    id                 UUID         PRIMARY KEY,
    media_asset_id     UUID         NOT NULL,
    source_candidate_id UUID,
    event_type         VARCHAR(40)  NOT NULL,
    timestamp_ms       BIGINT       NOT NULL,
    start_time_ms      BIGINT       NOT NULL,
    end_time_ms        BIGINT       NOT NULL,
    note               VARCHAR(2000),
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_ground_truth_events_asset FOREIGN KEY (media_asset_id)
        REFERENCES media_assets (id) ON DELETE CASCADE,
    CONSTRAINT fk_ground_truth_events_candidate FOREIGN KEY (source_candidate_id)
        REFERENCES candidate_events (id) ON DELETE SET NULL,
    CONSTRAINT ck_ground_truth_events_type CHECK
        (event_type IN ('GOAL', 'BIG_CHANCE', 'SHOT', 'SAVE', 'PENALTY', 'MISSED_PENALTY',
                        'RED_CARD', 'YELLOW_CARD', 'VAR', 'FOUL', 'COUNTER_ATTACK', 'ATTACK',
                        'NEAR_MISS', 'CELEBRATION', 'CROWD_REACTION', 'COMMENTATOR_REACTION',
                        'CONTROVERSIAL_DECISION', 'DRAMATIC_MOMENT', 'UNKNOWN')),
    CONSTRAINT ck_ground_truth_events_timestamps CHECK
        (timestamp_ms >= 0 AND start_time_ms >= 0 AND end_time_ms > start_time_ms
         AND timestamp_ms BETWEEN start_time_ms AND end_time_ms)
);

CREATE INDEX ix_ground_truth_events_asset_timestamp
    ON ground_truth_events (media_asset_id, timestamp_ms);

CREATE TABLE media_processing_stage_runs
(
    media_asset_id UUID         NOT NULL,
    stage          VARCHAR(32)  NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    progress       INTEGER,
    message        VARCHAR(1000),
    updated_at     TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (media_asset_id, stage),
    CONSTRAINT fk_media_processing_stage_runs_asset FOREIGN KEY (media_asset_id)
        REFERENCES media_assets (id) ON DELETE CASCADE,
    CONSTRAINT ck_media_processing_stage_runs_stage CHECK
        (stage IN ('AUDIO_EXTRACTION', 'TRANSCRIPTION')),
    CONSTRAINT ck_media_processing_stage_runs_status CHECK
        (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_media_processing_stage_runs_progress CHECK
        (progress IS NULL OR progress BETWEEN 0 AND 100)
);
