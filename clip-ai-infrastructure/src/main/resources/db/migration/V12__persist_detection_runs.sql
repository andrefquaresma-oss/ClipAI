CREATE TABLE detection_runs
(
    id                  UUID          PRIMARY KEY,
    media_asset_id      UUID          NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    status              VARCHAR(16)   NOT NULL,
    detector_version    VARCHAR(120)  NOT NULL,
    configuration_hash  VARCHAR(64)   NOT NULL,
    candidate_count     INTEGER       NOT NULL DEFAULT 0,
    observation_count   INTEGER       NOT NULL DEFAULT 0,
    detected_count      INTEGER       NOT NULL DEFAULT 0,
    rejected_count      INTEGER       NOT NULL DEFAULT 0,
    failure_reason      TEXT,
    CONSTRAINT fk_detection_runs_media_asset FOREIGN KEY (media_asset_id)
        REFERENCES media_assets (id) ON DELETE CASCADE,
    CONSTRAINT ck_detection_runs_status CHECK
        (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_detection_runs_counters CHECK
        (candidate_count >= 0 AND observation_count >= 0 AND detected_count >= 0
         AND rejected_count >= 0 AND detected_count + rejected_count <= candidate_count),
    CONSTRAINT ck_detection_runs_lifecycle CHECK
        ((status = 'PENDING' AND started_at IS NULL AND completed_at IS NULL AND failure_reason IS NULL)
         OR (status = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL AND failure_reason IS NULL)
         OR (status = 'COMPLETED' AND started_at IS NOT NULL AND completed_at IS NOT NULL
             AND failure_reason IS NULL)
         OR (status = 'FAILED' AND completed_at IS NOT NULL AND failure_reason IS NOT NULL
             AND length(btrim(failure_reason)) > 0))
);

CREATE UNIQUE INDEX ux_detection_runs_asset_active
    ON detection_runs (media_asset_id)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX ix_detection_runs_asset_created
    ON detection_runs (media_asset_id, created_at DESC, id);

ALTER TABLE candidate_events
    ADD COLUMN detection_run_id UUID,
    ADD CONSTRAINT fk_candidate_events_detection_run FOREIGN KEY (detection_run_id)
        REFERENCES detection_runs (id) ON DELETE RESTRICT;
CREATE INDEX ix_candidate_events_run_time
    ON candidate_events (detection_run_id, trigger_timestamp_ms, id)
    WHERE detection_run_id IS NOT NULL;

ALTER TABLE candidate_observations
    ADD COLUMN detection_run_id UUID,
    ADD CONSTRAINT fk_candidate_observations_detection_run FOREIGN KEY (detection_run_id)
        REFERENCES detection_runs (id) ON DELETE RESTRICT;
CREATE INDEX ix_candidate_observations_run_timeline
    ON candidate_observations (detection_run_id, timestamp_ms, signal_type)
    WHERE detection_run_id IS NOT NULL;
CREATE INDEX ix_candidate_observations_legacy_timeline
    ON candidate_observations (media_asset_id, timestamp_ms, signal_type)
    WHERE detection_run_id IS NULL;
