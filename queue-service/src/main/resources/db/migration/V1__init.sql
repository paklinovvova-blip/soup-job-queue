-- Schema of the training-job queue. See openspec/changes/add-job-queue-service/design.md (D4).

CREATE TABLE jobs (
    id               uuid        PRIMARY KEY,
    idempotency_key  text        NOT NULL UNIQUE,
    request_hash     text        NOT NULL,              -- sha256 of the canonical JSON payload
    payload          jsonb       NOT NULL,
    status           text        NOT NULL
                     CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
    attempts         int         NOT NULL DEFAULT 0,
    max_attempts     int         NOT NULL DEFAULT 3,
    lease_token      bigint      NOT NULL DEFAULT 0,    -- fencing token: +1 on every transition except running -> succeeded
    lease_owner      text,
    lease_expires_at timestamptz,
    claimed_at       timestamptz,                       -- start of the current attempt (max runtime)
    run_after        timestamptz NOT NULL DEFAULT now(),
    last_error       text,                              -- error of the most recent failed attempt
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    started_at       timestamptz,                       -- first claim
    finished_at      timestamptz
);

-- Hot paths only: the claim scans queued jobs, the reaper scans running jobs.
CREATE INDEX jobs_queued_idx      ON jobs (run_after, created_at) WHERE status = 'queued';
CREATE INDEX jobs_running_idx     ON jobs (lease_expires_at)      WHERE status = 'running';
CREATE INDEX jobs_list_idx        ON jobs (created_at DESC, id DESC);
CREATE INDEX jobs_status_list_idx ON jobs (status, created_at DESC, id DESC);

-- One row per real run of a job. Counts executions, not effects.
CREATE TABLE executions (
    id          bigserial   PRIMARY KEY,
    job_id      uuid        NOT NULL REFERENCES jobs (id),
    lease_token bigint      NOT NULL,
    worker_id   text        NOT NULL,
    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    outcome     text CHECK (outcome IN ('succeeded', 'failed', 'fenced', 'cancelled'))  -- NULL = worker died
);
CREATE INDEX executions_job_idx ON executions (job_id);

-- The emulated side effect of a training job. The primary key makes "at most one effect per job"
-- a database rule, independent of the fencing check in application code.
CREATE TABLE effects (
    job_id      uuid        PRIMARY KEY REFERENCES jobs (id),
    lease_token bigint      NOT NULL,
    worker_id   text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- Audit log of state transitions, written by a trigger so that no application code path
-- (including mutant modes) can skip it. Used by chaos invariant I9.
CREATE TABLE job_transitions (
    id         bigserial   PRIMARY KEY,
    job_id     uuid        NOT NULL,
    old_status text        NOT NULL,
    new_status text        NOT NULL,
    old_token  bigint      NOT NULL,
    new_token  bigint      NOT NULL,
    at         timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX job_transitions_job_idx ON job_transitions (job_id);

CREATE FUNCTION log_job_transition() RETURNS trigger AS $$
BEGIN
    -- Heartbeats change neither status nor token, so they are not logged.
    -- Any update of a terminal job is logged, because it must never happen.
    IF NEW.status IS DISTINCT FROM OLD.status
       OR NEW.lease_token IS DISTINCT FROM OLD.lease_token
       OR OLD.status IN ('succeeded', 'failed', 'cancelled') THEN
        INSERT INTO job_transitions (job_id, old_status, new_status, old_token, new_token)
        VALUES (OLD.id, OLD.status, NEW.status, OLD.lease_token, NEW.lease_token);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER jobs_transition_audit
    AFTER UPDATE ON jobs
    FOR EACH ROW EXECUTE FUNCTION log_job_transition();
