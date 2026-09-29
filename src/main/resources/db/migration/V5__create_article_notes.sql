CREATE TABLE article_note (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    news_id BIGINT NOT NULL REFERENCES news(id),
    content VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_article_note_user_updated_at ON article_note (user_id, updated_at DESC);
CREATE INDEX idx_article_note_news_user_updated_at ON article_note (news_id, user_id, updated_at DESC);
