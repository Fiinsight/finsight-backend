-- Local migration: explicit SQL, existing ddl-auto=update/Flyway=false unchanged.
-- Unique(user_id, news_id) is intentionally deferred until approved data cleanup.
ALTER TABLE judgement ADD COLUMN IF NOT EXISTS comparison_date date;
ALTER TABLE judgement ADD COLUMN IF NOT EXISTS judgement_close numeric(24,8);
ALTER TABLE judgement ADD COLUMN IF NOT EXISTS comparison_close numeric(24,8);
