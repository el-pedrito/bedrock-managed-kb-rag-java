#!/usr/bin/env bash
# Pose une question a l'API locale.
# Usage : ./scripts/ask.sh "Que signifie le code F28 ?" ["Condensa 24"]
set -euo pipefail
QUESTION="${1:?question requise}"
MODEL="${2:-}"
BODY=$(jq -n --arg q "$QUESTION" --arg m "$MODEL" \
  'if $m == "" then {question: $q} else {question: $q, equipmentModel: $m} end')
curl -s -X POST "${API_URL:-http://localhost:8080}/api/ask" \
  -H "Content-Type: application/json" -d "$BODY" | jq .
