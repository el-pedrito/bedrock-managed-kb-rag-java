data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
}

# ---------- Role de service de la Knowledge Base ----------

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

# ---------- Knowledge Base managee ----------
# Le service gere le parsing, le modele d'embedding, le stockage et la recherche hybride.
# Requetes : Retrieve avec managedSearchConfiguration (RetrieveAndGenerate n'existe pas ici).

resource "aws_bedrockagent_knowledge_base" "docs" {
  name        = "${var.project_name}-kb"
  description = "Documentation technique fournisseurs (notices, codes defauts, procedures)"
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
    # Une Knowledge Base managee utilise les connecteurs managees, pas la source "S3" classique.
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
          # Valeur par defaut du service, declaree pour que Terraform ne voie pas de derive.
          maxFileSizeInMegaBytes = "500"
        }
        aclEnabled = false
      })

      # Extraction des images (schemas, tableaux en image) : activee par defaut par le service.
      # Utile pour des notices fournisseurs pleines de schemas.
      media_extraction_configuration {
        image_extraction_configuration {
          image_extraction_status = "ENABLED"
        }
      }
    }
  }
}
