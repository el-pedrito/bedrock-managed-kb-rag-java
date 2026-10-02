# ---------- Source documentation bucket ----------

# Demo: SSE-S3 encryption, no cross-Region replication, access logging or notifications.
# Add them before going to production (see README, "Going to production").
resource "aws_s3_bucket" "docs" {
  #checkov:skip=CKV_AWS_145:demo, SSE-S3 is enough (KMS CMK in production)
  #checkov:skip=CKV_AWS_144:single-Region demo
  #checkov:skip=CKV_AWS_18:demo, access logging in production
  #checkov:skip=CKV2_AWS_62:demo, no event processing
  bucket_prefix = "${var.project_name}-docs-"
  # Demo: the bucket and its versions are deleted by terraform destroy.
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

# ---------- Documentation (manuals + metadata) ----------
# Each manual has a <manual>.metadata.json file next to it: the Knowledge Base reads its
# attributes (model, manufacturer), used to filter by equipment.

locals {
  # fileset accepts {a,b} alternatives (Terraform fileset docs): checked, 8 files found
  # in sample-docs/ (4 manuals + 4 metadata files).
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
