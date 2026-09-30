ALTER TABLE candidate_events
    ADD COLUMN score_contributions JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_candidate_events_score_contributions
        CHECK (jsonb_typeof(score_contributions) = 'array');

ALTER TABLE candidate_events
    DROP CONSTRAINT ck_candidate_events_type,
    ADD CONSTRAINT ck_candidate_events_type CHECK
        (event_type IN ('GOAL', 'GOAL_DISALLOWED', 'BIG_CHANCE', 'SHOT', 'SAVE', 'PENALTY',
                        'MISSED_PENALTY', 'PENALTY_MISSED', 'CORNER', 'FREE_KICK', 'OFFSIDE',
                        'RED_CARD', 'YELLOW_CARD', 'SUBSTITUTION', 'VAR', 'VAR_REVIEW', 'VAR_DECISION',
                        'REFEREE_WHISTLE', 'BROADCAST_START', 'KICKOFF', 'HALF_TIME',
                        'SECOND_HALF_START', 'FULL_TIME', 'BROADCAST_END', 'REPLAY_START', 'REPLAY_END',
                        'COMMENTARY_EVENT', 'FOUL', 'COUNTER_ATTACK', 'ATTACK', 'NEAR_MISS', 'CELEBRATION',
                        'CROWD_REACTION', 'COMMENTATOR_REACTION', 'CONTROVERSIAL_DECISION',
                        'DRAMATIC_MOMENT', 'UNKNOWN'));

CREATE TABLE candidate_observations
(
    id              UUID             PRIMARY KEY,
    media_asset_id  UUID             NOT NULL,
    timestamp_ms    BIGINT           NOT NULL,
    signal_type     VARCHAR(48)      NOT NULL,
    event_type      VARCHAR(40),
    confidence      DOUBLE PRECISION NOT NULL,
    evidence        TEXT             NOT NULL,
    observed_at     TIMESTAMPTZ      NOT NULL,
    CONSTRAINT fk_candidate_observations_asset FOREIGN KEY (media_asset_id)
        REFERENCES media_assets (id) ON DELETE CASCADE,
    CONSTRAINT ck_candidate_observations_timestamp CHECK (timestamp_ms >= 0),
    CONSTRAINT ck_candidate_observations_confidence CHECK (confidence BETWEEN 0 AND 1)
);

CREATE INDEX ix_candidate_observations_asset_timeline
    ON candidate_observations (media_asset_id, timestamp_ms, signal_type);
