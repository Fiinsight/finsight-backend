-- New application evidence only; existing definition_only mastery and historical answers stay intact.
CREATE TABLE term_application_mastery (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    term VARCHAR(100) NOT NULL,
    question_type VARCHAR(20) NOT NULL CHECK (question_type IN ('DIRECTION','FACT_CHECK')),
    p_known DOUBLE PRECISION NOT NULL CHECK (p_known BETWEEN 0 AND 1),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_answered_at TIMESTAMPTZ NOT NULL,
    last_correct BOOLEAN NOT NULL,
    PRIMARY KEY (user_id,term,question_type)
);
