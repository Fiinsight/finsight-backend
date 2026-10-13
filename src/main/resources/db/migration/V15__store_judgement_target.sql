ALTER TABLE judgement ADD COLUMN IF NOT EXISTS target_symbol varchar(16);
-- Existing judgements remain untouched until a separately approved regrade.
