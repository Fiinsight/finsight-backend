-- Store publisher-hosted RSS/og:image references, never image files.
ALTER TABLE news ADD COLUMN image_url VARCHAR(2000);
