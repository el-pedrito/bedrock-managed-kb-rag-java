variable "project_name" {
  description = "Prefixe des ressources."
  type        = string
  default     = "techassist-rag"

  validation {
    condition     = can(regex("^[a-z0-9-]{3,30}$", var.project_name))
    error_message = "Minuscules, chiffres et tirets, 3 a 30 caracteres."
  }
}

variable "region" {
  description = "Region de la Knowledge Base et du point d'entree Bedrock."
  type        = string
  default     = "eu-west-1"
}

variable "model_id" {
  description = "Profil d'inference europeen (prefixe eu.) utilise par l'application."
  type        = string
  default     = "eu.anthropic.claude-haiku-4-5-20251001-v1:0"

  validation {
    condition     = startswith(var.model_id, "eu.")
    error_message = "Utiliser un profil d'inference eu. pour garder l'inference en Europe."
  }
}

variable "grounding_threshold" {
  description = <<-EOT
    Seuil d'ancrage du garde-fou (0 a 0.99). Plus il est haut, plus les reponses peu fondees sont
    bloquees. Mesure sur la documentation d'exemple : reponses fideles entre 0,65 (liste de
    consignes de securite) et 1,0. A 0,7, la consigne gaz etait bloquee a tort : 0,5 par defaut,
    a recalibrer sur le jeu d'evaluation reel.
  EOT
  type        = number
  default     = 0.5

  validation {
    condition     = var.grounding_threshold >= 0 && var.grounding_threshold < 1
    error_message = "Valeur entre 0 et 0.99."
  }
}

variable "relevance_threshold" {
  description = "Seuil de pertinence du garde-fou (0 a 0.99)."
  type        = number
  default     = 0.5

  validation {
    condition     = var.relevance_threshold >= 0 && var.relevance_threshold < 1
    error_message = "Valeur entre 0 et 0.99."
  }
}

variable "application_role_name" {
  description = "Role IAM existant du backend auquel attacher la politique applicative. Vide = non attachee."
  type        = string
  default     = ""
}

variable "docs_path" {
  description = "Dossier local de la documentation chargee dans le bucket (notices + fichiers .metadata.json)."
  type        = string
  default     = "../sample-docs"
}

variable "account_id" {
  description = "Compte AWS cible attendu (12 chiffres). Terraform refuse tout autre compte."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "Identifiant de compte AWS attendu (12 chiffres)."
  }
}
