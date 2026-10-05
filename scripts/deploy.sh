#!/usr/bin/env bash
# Deploys the infrastructure (Terraform), indexes the documentation and writes the local
# configuration of the application.
# Usage: AWS_PROFILE=<profile> EXPECTED_ACCOUNT_ID=<account> ./scripts/deploy.sh
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
INFRA="$ROOT/infra"
OUT_DIR="$ROOT/.deploy"
mkdir -p "$OUT_DIR"

echo "1/3 terraform apply (bucket, documentation, managed Knowledge Base, guardrail, IAM)"
terraform -chdir="$INFRA" init -input=false -upgrade=false > /dev/null
terraform -chdir="$INFRA" apply -input=false -auto-approve

tf() { terraform -chdir="$INFRA" output -raw "$1"; }
REGION=$(tf region)
KB_ID=$(tf knowledge_base_id)
DS_ID=$(tf data_source_id)

echo "2/3 Indexing (ingestion job)"
# On a managed Knowledge Base, the data source goes CREATING -> AVAILABLE in a few minutes.
for _ in $(seq 1 40); do
  DS_STATUS=$(aws bedrock-agent get-data-source --region "$REGION" \
    --knowledge-base-id "$KB_ID" --data-source-id "$DS_ID" --query dataSource.status --output text)
  [ "$DS_STATUS" = "AVAILABLE" ] && break
  echo "   data source: $DS_STATUS"
  sleep 15
done
[ "$DS_STATUS" = "AVAILABLE" ] || { echo "Data source not available: $DS_STATUS"; exit 1; }

JOB_ID=$(aws bedrock-agent start-ingestion-job --region "$REGION" \
  --knowledge-base-id "$KB_ID" --data-source-id "$DS_ID" \
  --query ingestionJob.ingestionJobId --output text)
STATUS=UNKNOWN
for _ in $(seq 1 80); do
  STATUS=$(aws bedrock-agent get-ingestion-job --region "$REGION" \
    --knowledge-base-id "$KB_ID" --data-source-id "$DS_ID" --ingestion-job-id "$JOB_ID" \
    --query ingestionJob.status --output text)
  echo "   indexing: $STATUS"
  case "$STATUS" in
    COMPLETE) break ;;
    FAILED|STOPPED) echo "Indexing failed (job $JOB_ID)"; exit 1 ;;
  esac
  sleep 15
done
[ "$STATUS" = "COMPLETE" ] || { echo "Indexing not finished after 20 min (job $JOB_ID, status $STATUS)"; exit 1; }
aws bedrock-agent get-ingestion-job --region "$REGION" \
  --knowledge-base-id "$KB_ID" --data-source-id "$DS_ID" --ingestion-job-id "$JOB_ID" \
  --query ingestionJob.statistics

echo "3/3 Local configuration in .deploy/outputs.sh"
# printf %q: escaped values, the file is then sourced by the shell.
{
  [ -n "${AWS_PROFILE:-}" ] && printf 'export AWS_PROFILE=%q\n' "$AWS_PROFILE"
  printf 'export AWS_REGION=%q\n' "$REGION"
  printf 'export KNOWLEDGE_BASE_ID=%q\n' "$KB_ID"
  printf 'export GUARDRAIL_ID=%q\n' "$(tf guardrail_id)"
  printf 'export GUARDRAIL_VERSION=%q\n' "$(tf guardrail_version)"
  printf 'export MODEL_ID=%q\n' "$(tf model_id)"
} > "$OUT_DIR/outputs.sh"
echo "Ready. Run: source .deploy/outputs.sh && mvn spring-boot:run"
