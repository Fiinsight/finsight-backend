ALTER TABLE judgement ADD COLUMN user_id BIGINT;
ALTER TABLE judgement ADD CONSTRAINT fk_judgement_user FOREIGN KEY (user_id) REFERENCES app_user(id);
CREATE INDEX idx_judgement_user_created_at ON judgement(user_id, created_at DESC);
