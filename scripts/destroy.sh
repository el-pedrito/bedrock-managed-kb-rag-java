#!/usr/bin/env bash
# Deletes every resource created by deploy.sh (including the bucket and its versions).
# Usage: AWS_PROFILE=<profile> EXPECTED_ACCOUNT_ID=<account> ./scripts/destroy.sh
set -euo pipefail

# Account guard: we only deploy (or destroy) in the expected account. Terraform checks it too
# (allowed_account_ids), including for an apply run by hand.
: "${EXPECTED_ACCOUNT_ID:?Set EXPECTED_ACCOUNT_ID, the target AWS account (12 digits)}"
read -r CALLER_ACCOUNT CALLER_ARN <<< "$(aws sts get-caller-identity --query '[Account,Arn]' --output text)"
if [ "$CALLER_ACCOUNT" != "$EXPECTED_ACCOUNT_ID" ]; then
  echo "Current account $CALLER_ACCOUNT differs from expected account $EXPECTED_ACCOUNT_ID: stopping." >&2
  exit 1
fi
echo "Account $CALLER_ACCOUNT, identity $CALLER_ARN"
export TF_VAR_account_id="$EXPECTED_ACCOUNT_ID"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

read -r -p "Delete the Knowledge Base, the documentation bucket and the guardrail? [yes/no] " CONFIRM
[ "$CONFIRM" = "yes" ] || { echo "Cancelled."; exit 0; }

terraform -chdir="$ROOT/infra" destroy -input=false -auto-approve
rm -f "$ROOT/.deploy/outputs.sh"
echo "Resources deleted."
