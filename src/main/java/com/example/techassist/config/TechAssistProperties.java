package com.example.techassist.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Assistant configuration, overridable with environment variables (see application.yaml).
 * The guardrail is mandatory: without GUARDRAIL_ID and GUARDRAIL_VERSION the application does not start.
 *
 * @param region            AWS Region of the Knowledge Base and of the Bedrock endpoint
 * @param knowledgeBaseId   managed Knowledge Base identifier
 * @param modelId           inference profile (eu. prefix to stay in Europe)
 * @param guardrailId       grounding check guardrail
 * @param guardrailVersion  published guardrail version
 * @param maxResults        number of passages retrieved
 * @param maxTokens         token cap of the answer
 */
@Validated
@ConfigurationProperties(prefix = "techassist")
public record TechAssistProperties(
        @NotBlank String region,
        @NotBlank String knowledgeBaseId,
        @NotBlank @Pattern(regexp = "eu\\..+", message = "eu. inference profile expected") String modelId,
        @NotBlank String guardrailId,
        @NotBlank String guardrailVersion,
        @Min(1) @Max(20) int maxResults,
        @Min(100) @Max(4000) int maxTokens) { }
