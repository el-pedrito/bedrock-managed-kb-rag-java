# ---------- Permissions de l'application (moindre privilege) ----------
# Politique a attacher au role IAM qui execute le backend (EC2, ECS, EKS, Lambda).

locals {
  # "eu.anthropic.claude-haiku-4-5-20251001-v1:0" -> "anthropic.claude-haiku-4-5-20251001-v1:0"
  base_model_id = trimprefix(var.model_id, "eu.")
}

data "aws_iam_policy_document" "application" {
  statement {
    sid       = "RetrieveFromKnowledgeBase"
    actions   = ["bedrock:Retrieve"]
    resources = [aws_bedrockagent_knowledge_base.docs.arn]
  }

  statement {
    sid = "InvokeEuropeanInferenceProfile"
    # Converse = bedrock:InvokeModel. Ajouter InvokeModelWithResponseStream seulement si
    # l'application passe en ConverseStream.
    actions = ["bedrock:InvokeModel"]
    resources = [
      "arn:${local.partition}:bedrock:${var.region}:${local.account_id}:inference-profile/${var.model_id}",
      # Un profil eu. route uniquement vers des regions europeennes.
      "arn:${local.partition}:bedrock:eu-*::foundation-model/${local.base_model_id}",
    ]
  }

  statement {
    sid       = "ApplyGroundingGuardrail"
    actions   = ["bedrock:ApplyGuardrail"]
    resources = [aws_bedrock_guardrail.grounding.guardrail_arn]
  }
}

resource "aws_iam_policy" "application" {
  name_prefix = "${var.project_name}-app-"
  description = "Acces minimal du backend : Retrieve, modele EU, garde-fou d'ancrage"
  policy      = data.aws_iam_policy_document.application.json
}

# Optionnel : attacher la politique au role existant qui execute le backend (EC2, ECS, EKS).
resource "aws_iam_role_policy_attachment" "application" {
  count      = var.application_role_name == "" ? 0 : 1
  role       = var.application_role_name
  policy_arn = aws_iam_policy.application.arn
}
