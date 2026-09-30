package com.example.techassist.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration de l'assistant. Toutes les valeurs sont surchargeables par variable
 * d'environnement (voir application.yaml).
 *
 * @param region            region AWS de la Knowledge Base et du point d'entree Bedrock
 * @param knowledgeBaseId   identifiant de la Managed Knowledge Base
 * @param modelId           profil d'inference (prefixe eu. pour rester en Europe)
 * @param guardrailId       garde-fou de controle d'ancrage (vide = desactive)
 * @param guardrailVersion  version publiee du garde-fou
 * @param maxResults        nombre de passages recuperes
 * @param maxTokens         plafond de tokens de la reponse
 */
@Validated
@ConfigurationProperties(prefix = "techassist")
public record TechAssistProperties(
        @NotBlank String region,
        @NotBlank String knowledgeBaseId,
        @NotBlank String modelId,
        String guardrailId,
        String guardrailVersion,
        @Min(1) @Max(20) int maxResults,
        @Min(100) @Max(4000) int maxTokens) {

    public boolean guardrailEnabled() {
        return guardrailId != null && !guardrailId.isBlank();
    }
}
