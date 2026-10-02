package com.example.techassist.answer;

import java.util.List;

/**
 * Reponse renvoyee a l'application mobile.
 *
 * @param answer     texte de la reponse
 * @param status     ANSWERED, NOT_FOUND (rien dans la doc) ou BLOCKED (controle d'ancrage)
 * @param sources    passages utilises, numerotes comme dans la reponse
 * @param usage      consommation, pour le suivi du cout par question
 * @param grounding  scores du controle d'ancrage (null si le garde-fou est desactive)
 */
public record Answer(String answer, Status status, List<Source> sources, Usage usage, Grounding grounding) {

    public enum Status { ANSWERED, NOT_FOUND, BLOCKED }

    public record Source(int index, String document, String modele, Double score) { }

    /**
     * @param modelId         modele utilise (null si aucun appel au modele)
     * @param inputTokens     tokens en entree
     * @param outputTokens    tokens en sortie
     * @param guardrailUnits  unites de texte facturees par le controle d'ancrage
     * @param latencyMs       temps total de traitement cote serveur
     */
    public record Usage(String modelId, int inputTokens, int outputTokens, int guardrailUnits, long latencyMs) { }

    /**
     * @param groundingScore  la reponse est-elle fondee sur la documentation (0 a 1)
     * @param relevanceScore  la reponse repond-elle a la question (0 a 1)
     */
    public record Grounding(Double groundingScore, Double relevanceScore) { }
}
