-- 검토용 초안. Flyway 경로 밖이며 운영에 실행하지 않았습니다.
-- 사용자 승인 후 별도 마이그레이션으로 승격하고 daily-credit-enabled=true를 설정해야 합니다.
CREATE TABLE IF NOT EXISTS learning_mastery_credit (
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
CREATE INDEX IF NOT EXISTS learning_mastery_credit_latest
 ON learning_mastery_credit(user_id,term,answered_at DESC);
-- 운영 기존 p_known을 재계산하거나 백필하지 않습니다.
