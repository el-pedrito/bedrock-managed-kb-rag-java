#!/usr/bin/env bash
# Deploie l'infrastructure, charge la documentation et lance l'indexation.
# Usage : ./scripts/deploy.sh   (variables optionnelles : AWS_REGION, AWS_PROFILE, STACK_NAME)
set -euo pipefail

REGION="${AWS_REGION:-eu-west-1}"
STACK="${STACK_NAME:-techassist-rag}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/.deploy"
mkdir -p "$OUT_DIR"

echo "1/4 Deploiement du stack $STACK dans $REGION"
aws cloudformation deploy \
  --region "$REGION" \
  --stack-name "$STACK" \
  --template-file "$ROOT/infra/template.yaml" \
  --capabilities CAPABILITY_IAM \
  --no-fail-on-empty-changeset

output() {
  aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
}
BUCKET=$(output DocsBucketName)
KB_ID=$(output KnowledgeBaseId)
DS_ID=$(output DataSourceId)

echo "2/4 Chargement de la documentation dans s3://$BUCKET/docs/"
aws s3 sync "$ROOT/sample-docs/" "s3://$BUCKET/docs/" --region "$REGION" --delete

echo "3/4 Indexation (ingestion job)"
JOB_ID=$(aws bedrock-agent start-ingestion-job --region "$REGION" \
  --knowledge-base-id "$KB_ID" --data-source-id "$DS_ID" \
  --query ingestionJob.ingestionJobId --output text)
while true; do
  STATUS=$(aws bedrock-agent get-ingestion-job --region "$REGION" \
    --knowledge-base-id "$KB_ID" --data-source-id "$DS_ID" --ingestion-job-id "$JOB_ID" \
    --query ingestionJob.status --output text)
  echo "   statut : $STATUS"
  case "$STATUS" in
    COMPLETE) break ;;
    FAILED|STOPPED) echo "Indexation en echec"; exit 1 ;;
  esac
  sleep 15
done
aws bedrock-agent get-ingestion-job --region "$REGION" \
  --knowledge-base-id "$KB_ID" --data-source-id "$DS_ID" --ingestion-job-id "$JOB_ID" \
  --query ingestionJob.statistics

echo "4/4 Ecriture de la configuration locale dans .deploy/outputs.sh"
cat > "$OUT_DIR/outputs.sh" <<EOF
export AWS_REGION=$REGION
export KNOWLEDGE_BASE_ID=$KB_ID
export GUARDRAIL_ID=$(output GuardrailId)
export GUARDRAIL_VERSION=$(output GuardrailVersion)
export MODEL_ID=$(output ModelId)
EOF
echo "Pret. Lancer : source .deploy/outputs.sh && mvn spring-boot:run"
