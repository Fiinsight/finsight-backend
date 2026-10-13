CREATE TABLE IF NOT EXISTS term_mastery (
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    term VARCHAR(100) NOT NULL,
    p_known DOUBLE PRECISION NOT NULL CHECK (p_known >= 0 AND p_known <= 1),
    attempts INTEGER NOT NULL CHECK (attempts >= 0),
    last_answered_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (user_id, term)
);
