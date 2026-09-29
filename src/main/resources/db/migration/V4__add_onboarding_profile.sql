ALTER TABLE app_user ADD COLUMN onboarding_answers_json TEXT;
ALTER TABLE app_user ADD COLUMN learning_level VARCHAR(30);
ALTER TABLE app_user ADD COLUMN learning_pace VARCHAR(30);
ALTER TABLE app_user ADD COLUMN learning_focus VARCHAR(30);
ALTER TABLE app_user ADD COLUMN daily_goal VARCHAR(100);
ALTER TABLE app_user ADD COLUMN onboarding_completed_at TIMESTAMP WITH TIME ZONE;
