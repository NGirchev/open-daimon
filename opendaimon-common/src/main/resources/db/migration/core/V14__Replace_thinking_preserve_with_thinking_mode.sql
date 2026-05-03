ALTER TABLE "user"
    ADD COLUMN IF NOT EXISTS thinking_mode VARCHAR(20);

UPDATE "user"
   SET thinking_mode = CASE
       WHEN thinking_preserve_enabled = TRUE THEN 'SHOW_ALL'
       ELSE 'HIDE_REASONING'
   END
   WHERE thinking_mode IS NULL;

ALTER TABLE "user"
    ALTER COLUMN thinking_mode SET NOT NULL,
    ALTER COLUMN thinking_mode SET DEFAULT 'HIDE_REASONING';

ALTER TABLE "user"
    DROP COLUMN IF EXISTS thinking_preserve_enabled;
