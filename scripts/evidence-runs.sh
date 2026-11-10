#!/usr/bin/env bash
# All evidence runs of task group 8, one after another. Every run writes its own raw log to runs/;
# a failing run does not stop the next one, and nothing is retried until green.
set -u
cd "$(dirname "$0")/.."
mvn -q -DskipTests package
JAR=chaos-harness/target/chaos-harness.jar
SUMMARY=runs/evidence-summary.txt
echo "evidence runs started $(date -u +%FT%TZ) at revision $(git rev-parse --short HEAD)" >> "$SUMMARY"

run() {
  local name=$1; shift
  echo ">>> $name: $*"
  java -jar "$JAR" "$@" > /dev/null 2>&1
  local code=$?
  local log
  log=$(ls -t runs/*.log | head -n1)
  local verdict
  verdict=$(grep -E '^(PASS|FAIL|INCONCLUSIVE|EXPECTED FAILURE|SHIP|DON.T SHIP|HARNESS ERROR)' "$log" | tail -n1)
  [ -z "$verdict" ] && verdict=$(grep -m1 'HARNESS ERROR' "$log")
  printf '%-28s exit=%s  %s  %s\n' "$name" "$code" "$log" "$verdict" | tee -a "$SUMMARY"
}

run "chaos none seed 1"      --jobs 10000 --seed 1
run "chaos none seed 2"      --jobs 10000 --seed 2
run "chaos none seed 3"      --jobs 10000 --seed 3
run "mutant naive-claim"     --jobs 10000 --seed 4 --mutant naive-claim
run "mutant no-fencing"      --jobs 10000 --seed 5 --mutant no-fencing
run "load 100 rps mixed"     --mode load --rps 100 --duration 5m
run "load 100 creates/s"     --mode load --rps 100 --duration 5m --mix creates
docker compose down -v > /dev/null 2>&1
echo "evidence runs finished $(date -u +%FT%TZ)" >> "$SUMMARY"
