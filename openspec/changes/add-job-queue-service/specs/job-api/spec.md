# Spec Delta

## Purpose

Public HTTP contract of the training-job queue: clients create jobs idempotently, read their status,
list them page by page, and cancel them, with predictable status codes for every outcome.

## ADDED Requirements

### Requirement: Job representation
Every endpoint that returns a job SHALL return a JSON object with `id` (UUID), `status`
(`queued`, `running`, `succeeded`, `failed` or `cancelled`), `payload`, `attempts`, `maxAttempts`,
`lastError` (nullable), `createdAt`, `updatedAt`, `startedAt` (nullable) and `finishedAt` (nullable).
Timestamps SHALL be ISO-8601 in UTC and come from the database clock.

#### Scenario: Fields of a fresh job
- **WHEN** a client fetches a job that has just been created
- **THEN** the response contains `status` = `queued`, `attempts` = 0, `startedAt` = null, `finishedAt` = null and a non-null `createdAt`

### Requirement: Error format
Every non-2xx response SHALL have a JSON body `{"error": "<machine_code>", "message": "<human text>"}`.
Error codes SHALL be stable strings: `missing_idempotency_key`, `idempotency_conflict`, `not_found`,
`invalid_request` and `job_finished`. A `job_finished` body SHALL also carry `job` (the current job representation),
and an `idempotency_conflict` body SHALL also carry `jobId` (the id of the job that owns the key).

#### Scenario: Unknown job
- **WHEN** a client requests `GET /jobs/{id}` for an id that does not exist
- **THEN** the response is 404 with `error` = `not_found`

### Requirement: Create requires an idempotency key
`POST /jobs` SHALL require an `Idempotency-Key` header of 1 to 200 printable ASCII characters.
The body SHALL be a JSON object `{"payload": <JSON object>}` whose serialized size is at most 16 KiB.

#### Scenario: Missing key
- **WHEN** a client sends `POST /jobs` without the `Idempotency-Key` header
- **THEN** the response is 400 with `error` = `missing_idempotency_key` and no job is created

#### Scenario: Payload too large
- **WHEN** a client sends a payload larger than 16 KiB
- **THEN** the response is 400 with `error` = `invalid_request` and no job is created

### Requirement: First create returns a new queued job
The first `POST /jobs` with a given idempotency key SHALL create exactly one job in state `queued`
and respond 201 with the job representation and a `Location: /jobs/{id}` header.

#### Scenario: New key
- **WHEN** a client sends `POST /jobs` with a key that was never used
- **THEN** the response is 201, the body has `status` = `queued`, and exactly one job with that key exists

### Requirement: Repeated create with the same key and body is idempotent
A `POST /jobs` that repeats an existing idempotency key with an identical payload SHALL NOT create
a job. It SHALL respond 200 with the existing job in its current state. This SHALL hold for
sequential retries and for concurrent requests that race on the same key.

#### Scenario: Retry after a timeout
- **WHEN** a client sends the same key and payload a second time after the first request succeeded
- **THEN** the response is 200 with the same `id` as the first response, and only one job exists for that key

#### Scenario: Concurrent duplicates
- **WHEN** several clients send the same key and payload at the same moment
- **THEN** exactly one request responds 201, all others respond 200, all responses carry the same `id`, and only one job exists for that key

#### Scenario: Retry after the job finished
- **WHEN** a client repeats the key of a job that is already `succeeded`
- **THEN** the response is 200 with that job and `status` = `succeeded`, and no new job is queued

### Requirement: Reusing a key with a different body is rejected
A `POST /jobs` that repeats an existing idempotency key with a payload that differs from the stored one
SHALL respond 409 with `error` = `idempotency_conflict` and the existing `jobId`, and SHALL NOT modify or create any job.
Payloads SHALL be compared in canonical form: object keys sorted recursively and numbers compared by numeric value.

#### Scenario: Same key, different payload
- **WHEN** a client sends an existing key with a different payload
- **THEN** the response is 409 with `error` = `idempotency_conflict`, `jobId` equal to the stored job's id, and the stored job is unchanged

#### Scenario: Numerically equal payload
- **WHEN** a client repeats an existing key with `{"lr": 1.0}` after first sending `{"lr": 1}`
- **THEN** the request is treated as a repeat and responds 200

#### Scenario: Same key, same payload with different key order
- **WHEN** a client repeats an existing key with the same payload fields in a different order
- **THEN** the request is treated as a repeat and responds 200

### Requirement: Get job status
`GET /jobs/{id}` SHALL return 200 with the current job representation.
A malformed id SHALL return 400 with `error` = `invalid_request`.

#### Scenario: Existing job
- **WHEN** a client requests an existing job id
- **THEN** the response is 200 with that job's current state

#### Scenario: Malformed id
- **WHEN** a client requests `GET /jobs/not-a-uuid`
- **THEN** the response is 400 with `error` = `invalid_request`

### Requirement: List jobs with cursor pagination
`GET /jobs` SHALL return `{"items": [...], "nextCursor": <string|null>}` ordered from newest to oldest
by creation time, with ties broken by id. `limit` SHALL default to 20 and be between 1 and 100.
An optional `status` filter SHALL restrict items to that state. `nextCursor` SHALL be null on the last page.

#### Scenario: Walk all pages
- **WHEN** a client follows `nextCursor` from the first page until it is null
- **THEN** every job that existed before the first request appears exactly once across the pages

#### Scenario: Inserts during paging
- **WHEN** new jobs are created while a client is paging
- **THEN** pages already in progress contain no duplicates and skip no job that existed before the first request

#### Scenario: Status filter
- **WHEN** a client requests `GET /jobs?status=failed`
- **THEN** every returned item has `status` = `failed`

#### Scenario: Invalid parameters
- **WHEN** a client sends `limit=0`, `limit=101`, an unknown `status` or a cursor that cannot be decoded
- **THEN** the response is 400 with `error` = `invalid_request`

### Requirement: Cancel a queued job
`POST /jobs/{id}/cancel` on a `queued` job SHALL move it to `cancelled` and respond 200 with the job.
The job SHALL never be executed afterwards.

#### Scenario: Cancel before start
- **WHEN** a client cancels a job that is still `queued`
- **THEN** the response is 200 with `status` = `cancelled`, and no execution of that job ever starts

### Requirement: Cancel a running job
`POST /jobs/{id}/cancel` on a `running` job SHALL move it to `cancelled` immediately and respond 200.
It SHALL revoke the current worker's lease, so that worker can no longer record an effect or change the job.

#### Scenario: Cancel during execution
- **WHEN** a client cancels a job while a worker is executing it
- **THEN** the response is 200 with `status` = `cancelled`, the job never gets an effect, and its final state stays `cancelled`

### Requirement: Cancel is idempotent and respects finished jobs
Cancelling a job that is already `cancelled` SHALL respond 200 with the job.
Cancelling a `succeeded` or `failed` job SHALL respond 409 with `error` = `job_finished` and the current job in the `job` field,
and SHALL NOT change it. Cancelling an unknown id SHALL respond 404.

#### Scenario: Cancel twice
- **WHEN** a client cancels the same job twice
- **THEN** both responses are 200 with `status` = `cancelled`

#### Scenario: Cancel loses the race to completion
- **WHEN** a client cancels a job whose completion has already committed
- **THEN** the response is 409 with `error` = `job_finished` and `job.status` = `succeeded`, the job stays `succeeded`, and it has exactly one effect

### Requirement: Cancel response matches the final state
The state returned by a cancel request SHALL be the state the job keeps forever:
a 200 `cancelled` response SHALL imply the job ends `cancelled` with no effect, and a 409 `job_finished` response SHALL imply
the job ends in the state reported in its `job` field.

#### Scenario: Cancel and completion race
- **WHEN** a cancel request and a worker's completion hit the same job at the same moment
- **THEN** exactly one of them wins, and the cancel response describes the job's final state
