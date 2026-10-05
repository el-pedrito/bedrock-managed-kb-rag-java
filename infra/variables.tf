variable "project_name" {
  description = "Resource name prefix."
  type        = string
  default     = "techassist-rag"

  validation {
    condition     = can(regex("^[a-z0-9-]{3,30}$", var.project_name))
    error_message = "Lowercase letters, digits and hyphens, 3 to 30 characters."
  }
}

variable "region" {
  description = "Region of the Knowledge Base and of the Bedrock endpoint."
  type        = string
  default     = "eu-west-1"
}

variable "model_id" {
  description = "European inference profile (eu. prefix) used by the application."
  type        = string
  default     = "eu.anthropic.claude-haiku-4-5-20251001-v1:0"

  validation {
    condition     = startswith(var.model_id, "eu.")
    error_message = "Use an eu. inference profile to keep inference in Europe."
  }
}

variable "grounding_threshold" {
  description = <<-EOT
    Guardrail grounding threshold (0 to 0.99). The higher it is, the more weakly grounded answers
    are blocked. Measured on the sample documentation: faithful answers between 0.65 (list of
    safety instructions) and 1.0. At 0.7 the gas instruction was wrongly blocked: 0.5 by default,
    to recalibrate on the real evaluation set.
  EOT
  type        = number
  default     = 0.5

  validation {
    condition     = var.grounding_threshold >= 0 && var.grounding_threshold < 1
    error_message = "Value between 0 and 0.99."
  }
}

variable "relevance_threshold" {
  description = "Guardrail relevance threshold (0 to 0.99)."
  type        = number
  default     = 0.5

  validation {
    condition     = var.relevance_threshold >= 0 && var.relevance_threshold < 1
    error_message = "Value between 0 and 0.99."
  }
}

variable "application_role_name" {
  description = "Existing backend IAM role to attach the application policy to. Empty = not attached."
  type        = string
  default     = ""
}

variable "docs_path" {
  description = "Local documentation folder uploaded to the bucket (manuals + .metadata.json files)."
  type        = string
  default     = "../sample-docs"
}

variable "account_id" {
  description = "Expected target AWS account (12 digits). Terraform refuses any other account."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "AWS account ID expected (12 digits)."
  }
}
