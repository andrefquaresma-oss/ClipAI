ALTER TABLE candidate_reviews
    DROP CONSTRAINT ck_candidate_reviews_human_rejection_reason;

ALTER TABLE candidate_reviews
    ADD CONSTRAINT ck_candidate_reviews_human_rejection_reason CHECK
        (human_rejection_reason IS NULL OR human_rejection_reason IN
            ('NOT_A_FOOTBALL_EVENT', 'PRE_MATCH_NOISE', 'HALF_TIME_NOISE', 'POST_MATCH_NOISE',
             'CROWD_REACTION', 'COMMENTATOR_EXCITEMENT', 'REPLAY', 'RETROSPECTIVE_COMMENTARY',
             'SHOT_NO_GOAL', 'SHOT_NOT_GOAL', 'NORMAL_PLAY', 'WRONG_EVENT_TYPE', 'DUPLICATE',
             'INSUFFICIENT_EVIDENCE', 'OTHER'));
