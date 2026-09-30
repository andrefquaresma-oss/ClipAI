CREATE TABLE transcript_segments
(
    id            UUID   PRIMARY KEY,
    transcript_id UUID   NOT NULL,
    sequence      INTEGER NOT NULL,
    start_time_ms BIGINT NOT NULL,
    end_time_ms   BIGINT NOT NULL,
    text          TEXT   NOT NULL,
    CONSTRAINT fk_transcript_segments_transcript FOREIGN KEY (transcript_id)
        REFERENCES transcripts (id) ON DELETE CASCADE,
    CONSTRAINT uq_transcript_segments_sequence UNIQUE (transcript_id, sequence),
    CONSTRAINT ck_transcript_segments_sequence CHECK (sequence >= 0),
    CONSTRAINT ck_transcript_segments_start CHECK (start_time_ms >= 0),
    CONSTRAINT ck_transcript_segments_order CHECK (end_time_ms > start_time_ms)
);

CREATE INDEX ix_transcript_segments_timestamp
    ON transcript_segments (transcript_id, start_time_ms, end_time_ms);
