CREATE TABLE scoreboard_analyses
(
    id                       UUID PRIMARY KEY,
    media_asset_id           UUID NOT NULL REFERENCES media_assets (id) ON DELETE CASCADE,
    created_at               TIMESTAMPTZ NOT NULL,
    started_at               TIMESTAMPTZ,
    completed_at             TIMESTAMPTZ,
    status                   VARCHAR(16) NOT NULL,
    ocr_model                VARCHAR(120) NOT NULL,
    sampled_frame_count      INTEGER NOT NULL DEFAULT 0,
    ocr_call_count           INTEGER NOT NULL DEFAULT 0,
    raw_observation_count    INTEGER NOT NULL DEFAULT 0,
    score_state_count        INTEGER NOT NULL DEFAULT 0,
    score_transition_count   INTEGER NOT NULL DEFAULT 0,
    score_reversal_count     INTEGER NOT NULL DEFAULT 0,
    processing_error_count   INTEGER NOT NULL DEFAULT 0,
    processing_duration_ms   BIGINT NOT NULL DEFAULT 0,
    failure_reason           TEXT,
    CONSTRAINT ck_scoreboard_analyses_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_scoreboard_analyses_counts
        CHECK (sampled_frame_count >= 0 AND ocr_call_count >= 0 AND raw_observation_count >= 0
               AND score_state_count >= 0 AND score_transition_count >= 0
               AND score_reversal_count >= 0 AND processing_error_count >= 0
               AND processing_duration_ms >= 0),
    CONSTRAINT ck_scoreboard_analyses_lifecycle
        CHECK ((status = 'PENDING' AND started_at IS NULL AND completed_at IS NULL AND failure_reason IS NULL)
            OR (status = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL AND failure_reason IS NULL)
            OR (status = 'COMPLETED' AND started_at IS NOT NULL AND completed_at IS NOT NULL
                AND failure_reason IS NULL)
            OR (status = 'FAILED' AND completed_at IS NOT NULL AND failure_reason IS NOT NULL
                AND length(btrim(failure_reason)) > 0))
);

CREATE UNIQUE INDEX ux_scoreboard_analyses_asset_active
    ON scoreboard_analyses (media_asset_id)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX ix_scoreboard_analyses_asset_created
    ON scoreboard_analyses (media_asset_id, created_at DESC, id);

CREATE TABLE scoreboard_observations
(
    id                       UUID PRIMARY KEY,
    analysis_id              UUID NOT NULL REFERENCES scoreboard_analyses (id) ON DELETE CASCADE,
    timestamp_ms             BIGINT NOT NULL,
    kind                     VARCHAR(32) NOT NULL,
    raw_text                 TEXT NOT NULL DEFAULT '',
    confidence               DOUBLE PRECISION,
    home_score               INTEGER,
    away_score               INTEGER,
    previous_home_score      INTEGER,
    previous_away_score      INTEGER,
    details                  JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_scoreboard_observations_timestamp CHECK (timestamp_ms >= 0),
    CONSTRAINT ck_scoreboard_observations_kind
        CHECK (kind IN ('OCR_OBSERVATION', 'SCORE_STATE', 'SCORE_TRANSITION', 'SCORE_REVERSAL')),
    CONSTRAINT ck_scoreboard_observations_confidence
        CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_scoreboard_observations_scores
        CHECK ((home_score IS NULL OR home_score BETWEEN 0 AND 15)
            AND (away_score IS NULL OR away_score BETWEEN 0 AND 15)
            AND (previous_home_score IS NULL OR previous_home_score BETWEEN 0 AND 15)
            AND (previous_away_score IS NULL OR previous_away_score BETWEEN 0 AND 15)),
    CONSTRAINT ck_scoreboard_observations_details CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX ix_scoreboard_observations_analysis_timeline
    ON scoreboard_observations (analysis_id, timestamp_ms, kind);
