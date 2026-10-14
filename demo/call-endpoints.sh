#!/usr/bin/env bash
# Calls each demo endpoint once. Open http://localhost:4318 to see what logss found.
set -euo pipefail
BASE="${1:-http://localhost:8080}"

echo "N+1 queries:                GET  /api/orders"
curl -fsS -o /dev/null "$BASE/api/orders"

echo "save() in a loop:           POST /api/products/import"
curl -fsS -o /dev/null -H 'Content-Type: application/json' \
  -d '{"supplier":"acme","count":12,"apiKey":"sk_live_not_a_real_key"}' "$BASE/api/products/import"

echo "Partner API that is down:   POST /api/partners/sync"
curl -fsS -o /dev/null -X POST "$BASE/api/partners/sync"

echo "Slow query:                 GET  /api/reports/sku-overlap"
curl -fsS -o /dev/null "$BASE/api/reports/sku-overlap"

echo "Healthy, for comparison:    GET  /api/orders/1"
curl -fsS -o /dev/null "$BASE/api/orders/1"

echo "Done. Requests appear in logss a few seconds after they finish."
