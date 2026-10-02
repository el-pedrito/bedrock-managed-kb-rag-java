package com.example.techassist.kb;

import org.springframework.ai.document.Document;

/**
 * Extrait de documentation renvoye par la Knowledge Base.
 *
 * @param text       contenu du passage
 * @param sourceUri  document d'origine (s3://...)
 * @param score      score de pertinence renvoye par la Knowledge Base
 * @param fabricant  metadonnee fabricant (peut etre null)
 * @param modele     metadonnee modele d'equipement (peut etre null)
 */
public record Passage(String text, String sourceUri, Double score, String fabricant, String modele) {

    public static Passage from(Document document) {
        var meta = document.getMetadata();
        return new Passage(document.getText(),
                (String) meta.get(ManagedKnowledgeBaseVectorStore.SOURCE_URI),
                document.getScore(),
                (String) meta.get("fabricant"),
                (String) meta.get("modele"));
    }

    /** Nom de fichier lisible pour la citation. */
    public String documentName() {
        if (sourceUri == null) {
            return "document inconnu";
        }
        int slash = sourceUri.lastIndexOf('/');
        return slash >= 0 ? sourceUri.substring(slash + 1) : sourceUri;
    }
}
