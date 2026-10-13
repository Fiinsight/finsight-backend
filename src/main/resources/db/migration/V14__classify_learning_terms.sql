ALTER TABLE term ADD COLUMN IF NOT EXISTS kind varchar(16) NOT NULL DEFAULT 'CONCEPT';

-- Initialize only the new classification metadata; existing learning history is preserved.
UPDATE term SET kind = 'TOPIC'
WHERE term IN ('반도체', 'AI', 'HBM', '투자', '수출', '수입', '원화') AND kind = 'CONCEPT';
UPDATE term SET kind = 'MARKET'
WHERE term IN ('코스피', '코스닥', '원달러') AND kind = 'CONCEPT';
