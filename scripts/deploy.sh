#!/usr/bin/env bash
# Deploie l'infrastructure (Terraform), lance l'indexation de la documentation et ecrit la
# configuration locale de l'application.
# Usage : AWS_PROFILE=<profil> EXPECTED_ACCOUNT_ID=<compte> ./scripts/deploy.sh
set -euo pipefail

# Garde-fou de compte : on ne deploie (ni ne supprime) que dans le compte attendu. Terraform le
# verifie aussi (allowed_account_ids), y compris pour un apply lance a la main.
: "${EXPECTED_ACCOUNT_ID:?Fournir EXPECTED_ACCOUNT_ID, le compte AWS cible (12 chiffres)}"
read -r CALLER_ACCOUNT CALLER_ARN <<< "$(aws sts get-caller-identity --query '[Account,Arn]' --output text)"
if [ "$CALLER_ACCOUNT" != "$EXPECTED_ACCOUNT_ID" ]; then
  echo "Compte courant $CALLER_ACCOUNT different du compte attendu $EXPECTED_ACCOUNT_ID : arret." >&2
  exit 1
fi
echo "Compte $CALLER_ACCOUNT, identite $CALLER_ARN"
export TF_VAR_account_id="$EXPECTED_ACCOUNT_ID"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INFRA="$ROOT/infra"
OUT_DIR="$ROOT/.deploy"
mkdir -p "$OUT_DIR"

echo "1/3 terraform apply (bucket, documentation, Knowledge Base managee, garde-fou, IAM)"
terraform -chdir="$INFRA" init -input=false -upgrade=false > /dev/null
terraform -chdir="$INFRA" apply -input=false -auto-approve

tf() { terraform -chdir="$INFRA" output -raw "$1"; }
REGION=$(tf region)
KB_ID=$(tf knowledge_base_id)
DS_ID=$(tf data_source_id)

echo "2/3 Indexation (ingestion job)"
# Sur une Knowledge Base managee, la source passe CREATING -> AVAILABLE en quelques minutes.
for _ in $(seq 1 40); do
  DS_STATUS=$(aws bedrock-agent get-data-source --region "$REGION" \
    --knowledge-base-id "$KB_ID" --data-source-id "$DS_ID" --query dataSource.status --output text)
  [ "$DS_STATUS" = "AVAILABLE" ] && break
  echo "   source de donnees : $DS_STATUS"
  sleep 15
done
[ "$DS_STATUS" = "AVAILABLE" ] || { echo "Source de donnees non disponible : $DS_STATUS"; exit 1; }

JOB_ID=$(aws bedrock-agent start-ingestion-job --region "$REGION" \
  --knowledge-base-id "$KB_ID" --data-source-id "$DS_ID" \
  --query ingestionJob.ingestionJobId --output text)
STATUS=UNKNOWN
for _ in $(seq 1 80); do
  STATUS=$(aws bedrock-agent get-ingestion-job --region "$REGION" \
    --knowledge-base-id "$KB_ID" --data-source-id "$DS_ID" --ingestion-job-id "$JOB_ID" \
    --query ingestionJob.status --output text)
  echo "   indexation : $STATUS"
  case "$STATUS" in
    COMPLETE) break ;;
    FAILED|STOPPED) echo "Indexation en echec (job $JOB_ID)"; exit 1 ;;
  esac
  sleep 15
done
[ "$STATUS" = "COMPLETE" ] || { echo "Indexation non terminee apres 20 min (job $JOB_ID, statut $STATUS)"; exit 1; }
aws bedrock-agent get-ingestion-job --region "$REGION" \
  --knowledge-base-id "$KB_ID" --data-source-id "$DS_ID" --ingestion-job-id "$JOB_ID" \
  --query ingestionJob.statistics

echo "3/3 Configuration locale dans .deploy/outputs.sh"
# printf %q : valeurs echappees, le fichier est ensuite source par le shell.
{
  [ -n "${AWS_PROFILE:-}" ] && printf 'export AWS_PROFILE=%q\n' "$AWS_PROFILE"
  printf 'export AWS_REGION=%q\n' "$REGION"
  printf 'export KNOWLEDGE_BASE_ID=%q\n' "$KB_ID"
  printf 'export GUARDRAIL_ID=%q\n' "$(tf guardrail_id)"
  printf 'export GUARDRAIL_VERSION=%q\n' "$(tf guardrail_version)"
  printf 'export MODEL_ID=%q\n' "$(tf model_id)"
} > "$OUT_DIR/outputs.sh"
echo "Pret. Lancer : source .deploy/outputs.sh && mvn spring-boot:run"
