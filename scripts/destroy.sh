#!/usr/bin/env bash
# Supprime toutes les ressources creees par deploy.sh (bucket et ses versions compris).
# Usage : AWS_PROFILE=<profil> EXPECTED_ACCOUNT_ID=<compte> ./scripts/destroy.sh
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

read -r -p "Supprimer la Knowledge Base, le bucket de documentation et le garde-fou ? [oui/non] " CONFIRM
[ "$CONFIRM" = "oui" ] || { echo "Annule."; exit 0; }

terraform -chdir="$ROOT/infra" destroy -input=false -auto-approve
rm -f "$ROOT/.deploy/outputs.sh"
echo "Ressources supprimees."
