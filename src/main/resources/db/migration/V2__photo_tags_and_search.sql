CREATE TABLE IF NOT EXISTS photo_tags (
    photo_id BIGINT NOT NULL,
    tag VARCHAR(100) NOT NULL
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_photo_tags_photo_id'
    ) THEN
        ALTER TABLE photo_tags
            ADD CONSTRAINT fk_photo_tags_photo_id
            FOREIGN KEY (photo_id) REFERENCES photos (id) ON DELETE CASCADE;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_photo_tags_photo_id ON photo_tags (photo_id);
CREATE INDEX IF NOT EXISTS idx_photo_tags_tag_lower ON photo_tags ((lower(tag)));
