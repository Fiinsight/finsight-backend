CREATE TABLE learning_mastery_credit (
 id BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
 user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 news_id BIGINT NOT NULL REFERENCES news(id) ON DELETE CASCADE,
 term VARCHAR(100) NOT NULL,
 quiz_day DATE NOT NULL,
 level VARCHAR(20) NOT NULL,
 correct BOOLEAN NOT NULL,
 question_type VARCHAR(20) NOT NULL,
 answered_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(user_id,news_id,term,quiz_day)
);
CREATE INDEX learning_mastery_credit_latest
 ON learning_mastery_credit(user_id,term,answered_at DESC,id DESC);
-- 운영 기존 p_known을 재계산하거나 백필하지 않습니다.
