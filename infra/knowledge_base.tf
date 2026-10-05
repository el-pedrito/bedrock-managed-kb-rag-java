data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
}

# ---------- Knowledge Base service role ----------

data "aws_iam_policy_document" "kb_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["bedrock.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${local.partition}:bedrock:${var.region}:${local.account_id}:knowledge-base/*"]
    }
  }
}

data "aws_iam_policy_document" "kb_read_docs" {
  statement {
    sid       = "ListDocsBucket"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.docs.arn]
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceAccount"
      values   = [local.account_id]
    }
  }
  statement {
    sid       = "ReadDocs"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.docs.arn}/*"]
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceAccount"
      values   = [local.account_id]
    }
  }
}

resource "aws_iam_role" "kb" {
  name_prefix        = "${var.project_name}-kb-"
  assume_role_policy = data.aws_iam_policy_document.kb_trust.json
}

resource "aws_iam_role_policy" "kb_read_docs" {
  name   = "read-documentation"
  role   = aws_iam_role.kb.id
  policy = data.aws_iam_policy_document.kb_read_docs.json
}

# ---------- Managed Knowledge Base ----------
# The service handles parsing, the embedding model, storage and hybrid search.
# Queries: Retrieve with managedSearchConfiguration (RetrieveAndGenerate does not exist here).

resource "aws_bedrockagent_knowledge_base" "docs" {
  name        = "${var.project_name}-kb"
  description = "Supplier technical documentation (manuals, fault codes, procedures)"
  role_arn    = aws_iam_role.kb.arn

  knowledge_base_configuration {
    type = "MANAGED"
    managed_knowledge_base_configuration {
      embedding_model_type = "MANAGED"
    }
  }

  depends_on = [aws_iam_role_policy.kb_read_docs]
}

resource "aws_bedrockagent_data_source" "docs" {
  knowledge_base_id    = aws_bedrockagent_knowledge_base.docs.id
  name                 = "${var.project_name}-s3-docs"
  data_deletion_policy = "DELETE"

  data_source_configuration {
    # A managed Knowledge Base uses managed connectors, not the classic "S3" data source.
    type = "MANAGED_KNOWLEDGE_BASE_CONNECTOR"
    managed_knowledge_base_connector_configuration {
      connector_parameters = jsonencode({
        type    = "S3"
        version = "1"
        connectionConfiguration = {
          bucketName           = aws_s3_bucket.docs.id
          bucketOwnerAccountId = local.account_id
        }
        filterConfiguration = {
          inclusionPrefixes = ["docs/"]
          # Service default, declared so that Terraform sees no drift.
          maxFileSizeInMegaBytes = "500"
        }
        aclEnabled = false
      })

      # Image extraction (diagrams, tables as images): enabled by default by the service.
      # Useful for supplier manuals full of diagrams.
      media_extraction_configuration {
        image_extraction_configuration {
          image_extraction_status = "ENABLED"
        }
      }
    }
  }
}
