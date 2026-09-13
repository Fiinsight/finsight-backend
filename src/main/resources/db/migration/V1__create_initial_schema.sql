CREATE TABLE news (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    url VARCHAR(1000) NOT NULL UNIQUE,
    source VARCHAR(255),
    published_at TIMESTAMP WITH TIME ZONE,
    raw_content TEXT,
    rewritten_beginner TEXT,
    rewritten_normal TEXT,
    rewritten_analyst TEXT,
    importance_reason TEXT,
    related_symbol VARCHAR(255),
    sentiment_hint VARCHAR(50),
    category VARCHAR(255),
    key_terms TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE term (
    id BIGSERIAL PRIMARY KEY,
    term VARCHAR(255) NOT NULL UNIQUE,
    short_definition TEXT
);

CREATE TABLE judgement (
    id BIGSERIAL PRIMARY KEY,
    news_id BIGINT NOT NULL REFERENCES news(id),
    choice VARCHAR(50) NOT NULL,
    reason_text TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    actual_direction VARCHAR(255),
    actual_change_percent DOUBLE PRECISION,
    feedback_text TEXT,
    feedback_generated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_news_published_at ON news (published_at);
CREATE INDEX idx_news_related_symbol_published_at ON news (related_symbol, published_at);
CREATE INDEX idx_judgement_created_at ON judgement (created_at);
CREATE INDEX idx_judgement_feedback_pending
    ON judgement (feedback_generated_at, created_at)
    WHERE feedback_generated_at IS NULL;
