-- 초안 전용. 운영 실행·자동 Flyway 적용·기존 로그 백필 없음.
CREATE TABLE reading_attendance (
 user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 news_id BIGINT NOT NULL REFERENCES news(id) ON DELETE CASCADE,
 read_day DATE NOT NULL,
 first_read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id,news_id,read_day)
);
CREATE INDEX reading_attendance_days ON reading_attendance(user_id,read_day DESC);
-- 서버만 Asia/Seoul 날짜를 계산하여 쓰고, 읽기/집계 API는 JWT 소유권으로 제한한다.
