ALTER TABLE candidate_events
    ADD COLUMN source_candidate_ids JSONB,
    ADD COLUMN merge_reason VARCHAR(500);

UPDATE candidate_events
SET source_candidate_ids = jsonb_build_array(id::text)
WHERE source_candidate_ids IS NULL;

ALTER TABLE candidate_events
    ALTER COLUMN source_candidate_ids SET NOT NULL,
    ADD CONSTRAINT ck_candidate_events_sources CHECK
        (jsonb_typeof(source_candidate_ids) = 'array'
         AND jsonb_array_length(source_candidate_ids) >= 1
         AND source_candidate_ids ? id::text
         AND (jsonb_array_length(source_candidate_ids) = 1 OR merge_reason IS NOT NULL));

CREATE INDEX ix_candidate_events_asset_status_type_time
    ON candidate_events (media_asset_id, detection_status, event_type, trigger_timestamp_ms);
