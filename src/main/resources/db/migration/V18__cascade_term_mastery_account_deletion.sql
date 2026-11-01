-- Apply to production only after the user's migration/deployment decision.
DO $$
DECLARE fk record;
BEGIN
 FOR fk IN SELECT conname FROM pg_constraint
   WHERE conrelid='term_mastery'::regclass AND confrelid='app_user'::regclass AND contype='f'
 LOOP
   EXECUTE format('ALTER TABLE term_mastery DROP CONSTRAINT %I',fk.conname);
 END LOOP;
 ALTER TABLE term_mastery ADD CONSTRAINT term_mastery_user_id_fkey
   FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE CASCADE;
END $$;
