-- Source provenance for new collection only; no backfill or existing data regeneration.
ALTER TABLE news ADD COLUMN IF NOT EXISTS rewrite_mode varchar(16);
