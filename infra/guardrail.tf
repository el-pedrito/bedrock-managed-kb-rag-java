# ---------- Contextual grounding guardrail ----------
# Applied by the application with ApplyGuardrail, after generation: the answer must be grounded
# in the retrieved documentation (GROUNDING) and relevant to the question (RELEVANCE).

resource "aws_bedrock_guardrail" "grounding" {
  name        = "${var.project_name}-grounding"
  description = "Blocks answers that are not grounded in the documentation or off topic"
  # Messages shown to French-speaking technicians.
  blocked_input_messaging   = "Je ne peux pas traiter cette demande."
  blocked_outputs_messaging = "Je ne peux pas donner de reponse fiable a partir de la documentation disponible."

  contextual_grounding_policy_config {
    filters_config {
      type      = "GROUNDING"
      threshold = var.grounding_threshold
    }
    filters_config {
      type      = "RELEVANCE"
      threshold = var.relevance_threshold
    }
  }
}

resource "aws_bedrock_guardrail_version" "grounding" {
  guardrail_arn = aws_bedrock_guardrail.grounding.guardrail_arn
  description   = "Version used by the application"

  # A new version is published whenever the guardrail changes.
  lifecycle {
    replace_triggered_by = [aws_bedrock_guardrail.grounding]
  }
}
