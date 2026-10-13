-- Tables already represented by JPA entities but absent from earlier migrations.
-- Additive schema only: no existing records are changed.
CREATE TABLE IF NOT EXISTS learning_progress (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    news_id BIGINT NOT NULL REFERENCES news(id),
    term VARCHAR(100) NOT NULL,
    level VARCHAR(20) NOT NULL,
    correct BOOLEAN NOT NULL,
    answered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_learning_progress_user_news_term UNIQUE (user_id, news_id, term)
);

CREATE TABLE IF NOT EXISTS daily_note (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    note_date DATE NOT NULL,
    content VARCHAR(280) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_daily_note_user_date UNIQUE (user_id, note_date)
);
