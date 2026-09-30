ALTER TABLE media_assets
    ADD COLUMN candidate_detection_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN candidate_detection_failure_reason TEXT,
    ADD CONSTRAINT ck_media_assets_candidate_detection_status CHECK
        (candidate_detection_status IN ('NOT_STARTED', 'PROCESSING', 'COMPLETED', 'FAILED'));

CREATE TABLE candidate_events
(
    id                  UUID          PRIMARY KEY,
    media_asset_id      UUID          NOT NULL,
    start_time_ms       BIGINT        NOT NULL,
    end_time_ms         BIGINT        NOT NULL,
    trigger_timestamp_ms BIGINT       NOT NULL,
    event_type          VARCHAR(40)   NOT NULL,
    candidate_score     NUMERIC(5, 4) NOT NULL,
    signals             JSONB         NOT NULL,
    transcript_context  TEXT,
    detection_status    VARCHAR(24)   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT fk_candidate_events_media_asset FOREIGN KEY (media_asset_id)
        REFERENCES media_assets (id) ON DELETE CASCADE,
    CONSTRAINT ck_candidate_events_timestamps CHECK
        (start_time_ms >= 0 AND end_time_ms > start_time_ms
         AND trigger_timestamp_ms >= start_time_ms AND trigger_timestamp_ms <= end_time_ms),
    CONSTRAINT ck_candidate_events_type CHECK
        (event_type IN ('GOAL', 'BIG_CHANCE', 'SHOT', 'SAVE', 'PENALTY', 'MISSED_PENALTY',
                        'RED_CARD', 'YELLOW_CARD', 'VAR', 'FOUL', 'COUNTER_ATTACK', 'ATTACK',
                        'NEAR_MISS', 'CELEBRATION', 'CROWD_REACTION', 'COMMENTATOR_REACTION',
                        'CONTROVERSIAL_DECISION', 'DRAMATIC_MOMENT', 'UNKNOWN')),
    CONSTRAINT ck_candidate_events_score CHECK
        (candidate_score >= 0 AND candidate_score <= 1),
    CONSTRAINT ck_candidate_events_signals CHECK (jsonb_typeof(signals) = 'array'),
    CONSTRAINT ck_candidate_events_detection_status CHECK
        (detection_status IN ('DETECTED', 'AI_ANALYZED', 'REJECTED'))
);

CREATE INDEX ix_candidate_events_asset_timestamp
    ON candidate_events (media_asset_id, start_time_ms, end_time_ms);
CREATE INDEX ix_candidate_events_asset_score
    ON candidate_events (media_asset_id, candidate_score DESC, start_time_ms);
