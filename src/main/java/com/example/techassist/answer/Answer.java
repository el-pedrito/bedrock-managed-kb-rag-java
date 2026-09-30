package com.example.techassist.answer;

import java.util.List;

/**
 * Reponse renvoyee a l'application mobile.
 *
 * @param answer      texte de la reponse
 * @param status      ANSWERED, NOT_FOUND (rien dans la doc) ou BLOCKED (garde-fou d'ancrage)
 * @param sources     passages utilises, numerotes comme dans la reponse
 * @param usage       consommation, pour le suivi du cout par question
 */
public record Answer(String answer, Status status, List<Source> sources, Usage usage) {

    public enum Status { ANSWERED, NOT_FOUND, BLOCKED }

    public record Source(int index, String document, String modele, Double score) { }

    /**
     * @param modelId      modele utilise (null si aucun appel au modele)
     * @param inputTokens  tokens en entree
     * @param outputTokens tokens en sortie
     * @param latencyMs    temps total de traitement cote serveur
     */
    public record Usage(String modelId, int inputTokens, int outputTokens, long latencyMs) { }
}
