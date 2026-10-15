CREATE TABLE user_watchlist (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    symbol VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_user_watchlist_symbol UNIQUE (user_id, symbol)
);
CREATE INDEX idx_user_watchlist_user_id ON user_watchlist (user_id);
