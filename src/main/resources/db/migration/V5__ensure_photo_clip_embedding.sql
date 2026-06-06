ALTER TABLE photos
    ADD COLUMN IF NOT EXISTS clip_embedding_json TEXT;

