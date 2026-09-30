ALTER TABLE candidate_reviews
    ADD COLUMN human_rejection_reason VARCHAR(40),
    ADD CONSTRAINT ck_candidate_reviews_human_rejection_reason CHECK
        (human_rejection_reason IS NULL OR human_rejection_reason IN
            ('NOT_A_FOOTBALL_EVENT', 'PRE_MATCH_NOISE', 'HALF_TIME_NOISE', 'CROWD_REACTION',
             'REPLAY', 'RETROSPECTIVE_COMMENTARY', 'SHOT_NO_GOAL', 'WRONG_EVENT_TYPE',
             'DUPLICATE', 'OTHER'));

CREATE TABLE match_structure_markers
(
    media_asset_id UUID        NOT NULL,
    marker_type    VARCHAR(32) NOT NULL,
    timestamp_ms   BIGINT      NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (media_asset_id, marker_type),
    CONSTRAINT fk_match_structure_markers_asset FOREIGN KEY (media_asset_id)
        REFERENCES media_assets (id) ON DELETE CASCADE,
    CONSTRAINT ck_match_structure_markers_type CHECK
        (marker_type IN ('BROADCAST_START', 'PRE_MATCH', 'KICKOFF', 'FIRST_HALF_START', 'HALF_TIME',
                         'SECOND_HALF_START', 'FULL_TIME', 'POST_MATCH', 'BROADCAST_END')),
    CONSTRAINT ck_match_structure_markers_timestamp CHECK (timestamp_ms >= 0)
);

CREATE INDEX ix_match_structure_markers_timeline
    ON match_structure_markers (media_asset_id, timestamp_ms);

CREATE TABLE match_score_transitions
(
    id             UUID        PRIMARY KEY,
    media_asset_id UUID        NOT NULL,
    timestamp_ms   BIGINT      NOT NULL,
    home_score     INTEGER     NOT NULL,
    away_score     INTEGER     NOT NULL,
    source         VARCHAR(24) NOT NULL,
    confidence     DOUBLE PRECISION,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_match_score_transitions_asset FOREIGN KEY (media_asset_id)
        REFERENCES media_assets (id) ON DELETE CASCADE,
    CONSTRAINT ck_match_score_transitions_timestamp CHECK (timestamp_ms >= 0),
    CONSTRAINT ck_match_score_transitions_scores CHECK (home_score >= 0 AND away_score >= 0),
    CONSTRAINT ck_match_score_transitions_source CHECK (source IN ('MANUAL', 'SYSTEM_INFERRED')),
    CONSTRAINT ck_match_score_transitions_confidence CHECK
        (confidence IS NULL OR confidence BETWEEN 0 AND 1)
);

CREATE INDEX ix_match_score_transitions_timeline
    ON match_score_transitions (media_asset_id, timestamp_ms);
