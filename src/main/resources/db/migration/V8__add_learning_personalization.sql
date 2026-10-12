ALTER TABLE app_user ADD COLUMN IF NOT EXISTS learning_pain_point VARCHAR(30);
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS goal_type VARCHAR(30);
-- Existing answers are derived on read; no bulk UPDATE.
