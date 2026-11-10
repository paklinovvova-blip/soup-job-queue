-- MUTANT no-fencing ONLY. Applied only when QUEUE_MUTANT=no-fencing (see MutantMigrations).
-- Removes the database rule "at most one effect per job", so a missing fencing check
-- shows up as a silent duplicate effect instead of a uniqueness error.
ALTER TABLE effects DROP CONSTRAINT effects_pkey;
CREATE INDEX effects_job_idx ON effects (job_id);
