ALTER TABLE news ADD COLUMN importance_reason_beginner TEXT;
ALTER TABLE news ADD COLUMN importance_reason_normal TEXT;
ALTER TABLE news ADD COLUMN importance_reason_analyst TEXT;

UPDATE news
SET importance_reason_normal = importance_reason
WHERE importance_reason IS NOT NULL AND importance_reason_normal IS NULL;
