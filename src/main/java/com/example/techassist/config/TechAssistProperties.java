package com.example.techassist.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration de l'assistant. Toutes les valeurs sont surchargeables par variable
 * d'environnement (voir application.yaml).
 *
 * @param region            region AWS de la Knowledge Base et du point d'entree Bedrock
 * @param knowledgeBaseId   identifiant de la Managed Knowledge Base
 * @param modelId           profil d'inference (prefixe eu. pour rester en Europe)
 * @param groundingCheck    controle d'ancrage actif (defaut). Le desactiver doit etre un choix
 *                          explicite : GROUNDING_CHECK=false
 * @param guardrailId       garde-fou de controle d'ancrage (obligatoire si groundingCheck)
 * @param guardrailVersion  version publiee du garde-fou (obligatoire si groundingCheck)
 * @param maxResults        nombre de passages recuperes
 * @param maxTokens         plafond de tokens de la reponse
 */
@Validated
@ConfigurationProperties(prefix = "techassist")
public record TechAssistProperties(
        @NotBlank String region,
        @NotBlank String knowledgeBaseId,
        @NotBlank @Pattern(regexp = "eu\\..+", message = "profil d'inference eu. attendu") String modelId,
        @DefaultValue("true") boolean groundingCheck,
        String guardrailId,
        String guardrailVersion,
        @Min(1) @Max(20) int maxResults,
        @Min(100) @Max(4000) int maxTokens) {

    public TechAssistProperties {
        // Fail fast au demarrage : un garde-fou annonce mais mal configure ne doit pas
        // laisser passer des reponses non controlees.
        if (groundingCheck && (isBlank(guardrailId) || isBlank(guardrailVersion))) {
            throw new IllegalArgumentException("Controle d'ancrage actif : GUARDRAIL_ID et GUARDRAIL_VERSION "
                    + "sont obligatoires (ou GROUNDING_CHECK=false pour le desactiver explicitement).");
        }
    }

    public boolean guardrailEnabled() {
        return groundingCheck;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
