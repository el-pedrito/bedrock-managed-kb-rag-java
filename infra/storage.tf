# ---------- Bucket de documentation source ----------

# Demo : chiffrement SSE-S3, pas de replication cross-region, d'access logging ni de
# notifications. A ajouter avant une mise en production (voir README, "Passage en production").
resource "aws_s3_bucket" "docs" {
  #checkov:skip=CKV_AWS_145:demo, SSE-S3 suffisant (KMS CMK en production)
  #checkov:skip=CKV_AWS_144:demo mono-region
  #checkov:skip=CKV_AWS_18:demo, access logging en production
  #checkov:skip=CKV2_AWS_62:demo, pas de traitement evenementiel
  bucket_prefix = "${var.project_name}-docs-"
  # Demo : le bucket et ses versions sont supprimes par terraform destroy.
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "docs" {
  bucket                  = aws_s3_bucket.docs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "docs" {
  bucket = aws_s3_bucket.docs.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "docs" {
  bucket = aws_s3_bucket.docs.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "docs" {
  bucket = aws_s3_bucket.docs.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "docs" {
  bucket = aws_s3_bucket.docs.id
  rule {
    id     = "expire-old-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
  depends_on = [aws_s3_bucket_versioning.docs]
}

data "aws_iam_policy_document" "docs_tls_only" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.docs.arn, "${aws_s3_bucket.docs.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "docs" {
  bucket     = aws_s3_bucket.docs.id
  policy     = data.aws_iam_policy_document.docs_tls_only.json
  depends_on = [aws_s3_bucket_public_access_block.docs]
}

# ---------- Documentation (notices + metadonnees) ----------
# Chaque notice a un fichier <notice>.metadata.json a cote d'elle : la Knowledge Base en lit
# les attributs (modele, fabricant) qui servent au filtrage par equipement.

locals {
  # fileset accepte les alternatives {a,b} (doc Terraform fileset) : verifie, 8 fichiers trouves
  # dans sample-docs/ (4 notices + 4 metadonnees).
  doc_files = fileset(var.docs_path, "**/*.{md,json,pdf,txt}")
  content_types = {
    md   = "text/markdown; charset=utf-8"
    json = "application/json"
    pdf  = "application/pdf"
    txt  = "text/plain; charset=utf-8"
  }
}

resource "aws_s3_object" "docs" {
  for_each = local.doc_files

  bucket       = aws_s3_bucket.docs.id
  key          = "docs/${each.value}"
  source       = "${var.docs_path}/${each.value}"
  source_hash  = filemd5("${var.docs_path}/${each.value}")
  content_type = local.content_types[reverse(split(".", each.value))[0]]

  depends_on = [aws_s3_bucket_server_side_encryption_configuration.docs]
}
