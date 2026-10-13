ALTER TABLE news ADD COLUMN IF NOT EXISTS sentiment_confidence DOUBLE PRECISION
    CHECK (sentiment_confidence >= 0 AND sentiment_confidence <= 1);
