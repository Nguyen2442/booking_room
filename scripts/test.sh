#!/usr/bin/env bash
# Runs the full test-suite (unit + integration + concurrency) against a throw-away PostgreSQL.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose -f docker-compose.test.yml up --abort-on-container-exit --exit-code-from tests 2>&1 | tee target-test.log \
  | grep -E "Tests run|FAIL|ERROR|BUILD|expected|Expecting" || true
status=${PIPESTATUS[0]}
docker compose -f docker-compose.test.yml down -v >/dev/null 2>&1 || true
exit "$status"
