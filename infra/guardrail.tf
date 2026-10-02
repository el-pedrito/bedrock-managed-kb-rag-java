# ---------- Garde-fou d'ancrage contextuel ----------
# Applique par l'application avec ApplyGuardrail, apres la generation : la reponse doit etre
# fondee sur la documentation recuperee (GROUNDING) et pertinente pour la question (RELEVANCE).

resource "aws_bedrock_guardrail" "grounding" {
  name                      = "${var.project_name}-grounding"
  description               = "Bloque les reponses non fondees sur la documentation ou hors sujet"
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
  description   = "Version utilisee par l'application"

  # Nouvelle version publiee a chaque changement de seuils.
  lifecycle {
    replace_triggered_by = [aws_bedrock_guardrail.grounding]
  }
}
