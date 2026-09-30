ALTER TABLE ground_truth_events
    DROP CONSTRAINT ck_ground_truth_events_type,
    ADD CONSTRAINT ck_ground_truth_events_type CHECK
        (event_type IN ('GOAL', 'GOAL_DISALLOWED', 'BIG_CHANCE', 'SHOT', 'SAVE', 'PENALTY',
                        'MISSED_PENALTY', 'RED_CARD', 'YELLOW_CARD', 'VAR', 'FOUL',
                        'COUNTER_ATTACK', 'ATTACK', 'NEAR_MISS', 'CELEBRATION',
                        'CROWD_REACTION', 'COMMENTATOR_REACTION', 'CONTROVERSIAL_DECISION',
                        'DRAMATIC_MOMENT', 'UNKNOWN'));
