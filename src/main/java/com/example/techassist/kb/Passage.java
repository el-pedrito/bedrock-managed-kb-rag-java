package com.example.techassist.kb;

import org.springframework.ai.document.Document;

/**
 * Documentation excerpt returned by the Knowledge Base.
 *
 * @param text          passage content
 * @param sourceUri     source document (s3://...)
 * @param score         relevance score returned by the Knowledge Base
 * @param manufacturer  manufacturer metadata (may be null)
 * @param model         equipment model metadata (may be null)
 */
public record Passage(String text, String sourceUri, Double score, String manufacturer, String model) {

    public static Passage from(Document document) {
        var meta = document.getMetadata();
        return new Passage(document.getText(),
                (String) meta.get(ManagedKnowledgeBaseVectorStore.SOURCE_URI),
                document.getScore(),
                (String) meta.get("manufacturer"),
                (String) meta.get("model"));
    }

    /** Readable file name for the citation. */
    public String documentName() {
        if (sourceUri == null) {
            return "unknown document";
        }
        int slash = sourceUri.lastIndexOf('/');
        return slash >= 0 ? sourceUri.substring(slash + 1) : sourceUri;
    }
}
