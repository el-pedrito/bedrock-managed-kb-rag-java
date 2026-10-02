output "region" {
  value = var.region
}

output "docs_bucket_name" {
  value = aws_s3_bucket.docs.id
}

output "knowledge_base_id" {
  value = aws_bedrockagent_knowledge_base.docs.id
}

output "data_source_id" {
  value = aws_bedrockagent_data_source.docs.data_source_id
}

output "guardrail_id" {
  value = aws_bedrock_guardrail.grounding.guardrail_id
}

output "guardrail_version" {
  value = aws_bedrock_guardrail_version.grounding.version
}

output "model_id" {
  value = var.model_id
}

output "application_policy_arn" {
  description = "To attach to the backend IAM role."
  value       = aws_iam_policy.application.arn
}
