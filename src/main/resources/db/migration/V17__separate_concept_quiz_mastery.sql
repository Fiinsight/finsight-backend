ALTER TABLE learning_progress ADD COLUMN question_type VARCHAR(20) NOT NULL DEFAULT 'LEGACY'
    CHECK (question_type IN ('DEFINITION','DIRECTION','FACT_CHECK','LEGACY'));
-- Before this migration, fallback definitions were always persisted at beginner level.
UPDATE learning_progress SET question_type = CASE level
    WHEN 'beginner' THEN 'DEFINITION' WHEN 'normal' THEN 'DIRECTION' WHEN 'analyst' THEN 'FACT_CHECK' ELSE 'LEGACY' END;
ALTER TABLE learning_progress DROP CONSTRAINT uk_learning_progress_user_news_term;
ALTER TABLE learning_progress ADD CONSTRAINT uk_learning_progress_user_news_term_type UNIQUE (user_id,news_id,term,question_type);
ALTER TABLE term_mastery ADD COLUMN definition_only BOOLEAN NOT NULL DEFAULT false;

-- Rebuild only from saved definition answers. The old table mixed reading and concept evidence.
-- learning_progress keeps the final answer per article/term, so lost repeated attempts cannot be recovered.
-- Unverifiable legacy snapshots remain stored, excluded from concept cards and promotion.
DO $$
DECLARE
    answer RECORD; prior DOUBLE PRECISION; saved_attempts INTEGER; at_time TIMESTAMPTZ; verified BOOLEAN;
    days INTEGER; known DOUBLE PRECISION; unknown DOUBLE PRECISION;
BEGIN
    FOR answer IN SELECT p.* FROM learning_progress p JOIN term t ON t.term=p.term
        WHERE p.question_type='DEFINITION' AND (t.kind='CONCEPT' OR (t.kind='MARKET' AND p.level='beginner'))
        ORDER BY p.answered_at,p.id
    LOOP
        INSERT INTO term_mastery(user_id,term,p_known,attempts) VALUES(answer.user_id,answer.term,0.3,0) ON CONFLICT DO NOTHING;
        SELECT m.p_known,m.attempts,m.last_answered_at,m.definition_only INTO prior,saved_attempts,at_time,verified
            FROM term_mastery m WHERE m.user_id=answer.user_id AND m.term=answer.term;
        IF NOT verified THEN prior := 0.3; saved_attempts := 0; at_time := NULL; END IF;
        days := CASE WHEN at_time IS NULL THEN 0 ELSE greatest(0,
            (answer.answered_at AT TIME ZONE 'Asia/Seoul')::date - (at_time AT TIME ZONE 'Asia/Seoul')::date) END;
        prior := 0.3 + (prior - 0.3) * power(0.97,days);
        known := prior * CASE WHEN answer.correct THEN 0.9 ELSE 0.1 END;
        unknown := (1-prior) * CASE WHEN answer.correct THEN 0.33 ELSE 0.67 END;
        UPDATE term_mastery SET p_known=known/(known+unknown)*0.85+0.15,attempts=saved_attempts+1,
            last_answered_at=answer.answered_at,definition_only=true WHERE user_id=answer.user_id AND term=answer.term;
    END LOOP;
END $$;
