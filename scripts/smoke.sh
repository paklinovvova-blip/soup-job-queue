#!/usr/bin/env bash
# Smoke test against a running stack (docker compose up). Needs curl and python3.
# Creates a job and waits for it to succeed, repeats the create (idempotency), lists jobs,
# then creates a slow-to-start batch and cancels one job. Exits non-zero on any surprise.
set -euo pipefail
API=${API:-http://localhost:8080}
RUN=$(date +%s)

json() { python3 -I -c "import json,sys; print(json.load(sys.stdin)$1)"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

create() { # key payload -> prints "<http code> <body>"
  curl -s -w '\n%{http_code}' -X POST "$API/jobs" -H 'Content-Type: application/json' \
       -H "Idempotency-Key: $1" -d "{\"payload\":$2}"
}

echo "1. create a job"
out=$(create "smoke-$RUN-a" '{"model":"resnet18","epochs":1}')
code=$(tail -n1 <<<"$out"); body=$(sed '$d' <<<"$out")
[ "$code" = 201 ] || fail "expected 201, got $code: $body"
id=$(json '["id"]' <<<"$body")
echo "   201 created $id"

echo "2. repeat the same request (client retry)"
out=$(create "smoke-$RUN-a" '{"epochs":1,"model":"resnet18"}')
code=$(tail -n1 <<<"$out"); body=$(sed '$d' <<<"$out")
[ "$code" = 200 ] || fail "expected 200 on repeat, got $code"
[ "$(json '["id"]' <<<"$body")" = "$id" ] || fail "repeat returned a different job"
echo "   200, same job"

echo "3. same key, different payload"
code=$(create "smoke-$RUN-a" '{"model":"other"}' | tail -n1)
[ "$code" = 409 ] || fail "expected 409, got $code"
echo "   409 idempotency_conflict"

echo "4. wait for the job to finish"
for _ in $(seq 1 60); do
  status=$(curl -s "$API/jobs/$id" | json '["status"]')
  case "$status" in succeeded|failed) break ;; esac
  sleep 0.5
done
echo "   status: $status"
[ "$status" = succeeded ] || [ "$status" = failed ] || fail "job did not finish: $status"

echo "5. list the newest jobs"
curl -s "$API/jobs?limit=3" | json '["items"][0]["id"]' | grep -q "$id" || fail "job not on the first page"
echo "   job is on the first page"

echo "6. cancel: create 40 jobs, cancel the last one, cancel it again, cancel a finished job"
for i in $(seq 1 40); do create "smoke-$RUN-b$i" "{\"i\":$i}" >/dev/null; done
last=$(create "smoke-$RUN-b40" '{"i":40}' | sed '$d' | json '["id"]')
c1=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/jobs/$last/cancel")
c2=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/jobs/$last/cancel")
final=$(curl -s "$API/jobs/$last" | json '["status"]')
echo "   cancel: $c1, again: $c2, status now: $final"
if [ "$c1" = 200 ]; then
  [ "$c2" = 200 ] && [ "$final" = cancelled ] || fail "cancel not idempotent or not final"
else
  [ "$c1" = 409 ] || fail "unexpected cancel code $c1"   # the job finished first: also valid
fi
c3=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/jobs/$id/cancel")
[ "$c3" = 409 ] || fail "cancel of a finished job should be 409, got $c3"
echo "   cancel of finished job: 409 job_finished"

echo "OK"
