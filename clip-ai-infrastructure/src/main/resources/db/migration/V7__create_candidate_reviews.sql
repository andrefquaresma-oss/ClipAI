CREATE TABLE candidate_reviews
(
    candidate_event_id       UUID         PRIMARY KEY,
    review_status            VARCHAR(20)  NOT NULL DEFAULT 'UNREVIEWED',
    manual_start_time_ms     BIGINT,
    manual_end_time_ms       BIGINT,
    updated_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_candidate_reviews_event FOREIGN KEY (candidate_event_id)
        REFERENCES candidate_events (id) ON DELETE CASCADE,
    CONSTRAINT ck_candidate_reviews_status CHECK
        (review_status IN ('UNREVIEWED', 'CONFIRMED', 'REJECTED')),
    CONSTRAINT ck_candidate_reviews_boundaries CHECK
        ((manual_start_time_ms IS NULL AND manual_end_time_ms IS NULL)
         OR (manual_start_time_ms >= 0 AND manual_end_time_ms > manual_start_time_ms))
);
