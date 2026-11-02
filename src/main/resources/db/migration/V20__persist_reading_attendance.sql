CREATE TABLE reading_record (
 user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 news_id BIGINT NOT NULL REFERENCES news(id) ON DELETE CASCADE,
 first_read_at TIMESTAMPTZ NOT NULL,
 last_read_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(user_id,news_id)
);
-- Historical daily article sets are retained even if a source article is removed.
CREATE TABLE reading_attendance (
 user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 read_day DATE NOT NULL,
 news_ids BIGINT[] NOT NULL DEFAULT '{}',
 PRIMARY KEY(user_id,read_day)
);
CREATE TABLE reading_import (
 user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 device_id VARCHAR(80) NOT NULL,
 imported_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(user_id,device_id)
);
