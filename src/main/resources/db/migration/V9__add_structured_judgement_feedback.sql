-- Nullable additions only; do not regenerate or update existing feedback.
ALTER TABLE judgement ADD COLUMN IF NOT EXISTS feedback_reasons JSONB;
ALTER TABLE judgement ADD COLUMN IF NOT EXISTS feedback_headline VARCHAR(255);
-- feedback_reasons stores {verdict, reasons:[{title,detail}], nextTip}.
-- feedback_text remains summary. Existing rows retain NULL structured fields.
