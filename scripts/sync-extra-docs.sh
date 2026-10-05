#!/usr/bin/env bash
# Index an extra set of documents (kept OUTSIDE this repo) into the demo Knowledge Base.
#
# Usage: ./scripts/sync-extra-docs.sh <local_dir> [s3_subprefix]
#
# - Files are uploaded under docs/<s3_subprefix>/ (default: extra), next to the
#   Terraform-managed sample docs. Terraform does not manage these keys, so
#   `terraform apply` leaves them alone; `destroy.sh` deletes them with the bucket.
# - The first folder level is used as the manufacturer (e.g. <local_dir>/Thermalys/x.pdf).
#   A metadata file is generated only when none exists next to the document, so a
#   hand-written <file>.metadata.json (with the exact "model") always wins.
# - Nothing from <local_dir> is ever written into the repository.
set -euo pipefail

SRC=${1:?usage: sync-extra-docs.sh <local_dir> [s3_subprefix]}
SUB=${2:-extra}
[ -d "$SRC" ] || { echo "Not a directory: $SRC"; exit 1; }

cd "$(dirname "$0")/.."
source .deploy/outputs.sh
BUCKET=$(terraform -chdir=infra output -raw docs_bucket_name)
DS_ID=$(terraform -chdir=infra output -raw data_source_id)

STAGE=$(mktemp -d)
echo "1/3 Staging documents and metadata in $STAGE"
python3 - "$SRC" "$STAGE" <<'PY'
import json, shutil, sys
from pathlib import Path
src, stage = Path(sys.argv[1]), Path(sys.argv[2])
keep = {".pdf", ".docx", ".doc", ".txt", ".md", ".html", ".xlsx", ".pptx", ".csv"}
n = 0
for f in sorted(src.rglob("*")):
    if not f.is_file() or f.suffix.lower() not in keep or f.name.startswith("."):
        continue
    rel = f.relative_to(src)
    out = stage / rel
    out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(f, out)
    meta_src = f.with_name(f.name + ".metadata.json")
    if meta_src.exists():
        shutil.copy2(meta_src, out.with_name(out.name + ".metadata.json"))
    else:
        manufacturer = rel.parts[0] if len(rel.parts) > 1 else "unknown"
        attrs = {"manufacturer": {"value": {"type": "STRING", "stringValue": manufacturer},
                                  "includeForEmbedding": True},
                 "document_type": {"value": {"type": "STRING", "stringValue": "client_document"},
                                   "includeForEmbedding": False}}
        out.with_name(out.name + ".metadata.json").write_text(
            json.dumps({"metadataAttributes": attrs}, ensure_ascii=False, indent=2))
    n += 1
print(f"   {n} documents staged")
PY

echo "2/3 Uploading to s3://$BUCKET/docs/$SUB/"
aws s3 sync "$STAGE" "s3://$BUCKET/docs/$SUB/" --region "$AWS_REGION" --delete --only-show-errors

echo "3/3 Ingestion job"
JOB_ID=$(aws bedrock-agent start-ingestion-job --region "$AWS_REGION" \
  --knowledge-base-id "$KNOWLEDGE_BASE_ID" --data-source-id "$DS_ID" \
  --query ingestionJob.ingestionJobId --output text)
while :; do
  STATUS=$(aws bedrock-agent get-ingestion-job --region "$AWS_REGION" \
    --knowledge-base-id "$KNOWLEDGE_BASE_ID" --data-source-id "$DS_ID" --ingestion-job-id "$JOB_ID" \
    --query ingestionJob.status --output text)
  case "$STATUS" in COMPLETE|FAILED|STOPPED) break ;; esac
  echo "   $STATUS"; sleep 20
done
aws bedrock-agent get-ingestion-job --region "$AWS_REGION" \
  --knowledge-base-id "$KNOWLEDGE_BASE_ID" --data-source-id "$DS_ID" --ingestion-job-id "$JOB_ID" \
  --query '{status: ingestionJob.status, stats: ingestionJob.statistics, failures: ingestionJob.failureReasons}'
