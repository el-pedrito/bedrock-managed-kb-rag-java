package com.example.techassist.kb;

import com.example.techassist.config.TechAssistProperties;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.FilterAttribute;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseQuery;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.ManagedSearchConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalFilter;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveRequest;

/**
 * Recherche dans la Managed Knowledge Base.
 *
 * <p>Point clef : une Knowledge Base managee n'accepte pas {@code vectorSearchConfiguration}
 * (reserve aux Knowledge Bases vectorielles) ni l'API RetrieveAndGenerate. On appelle donc
 * {@code Retrieve} avec {@code managedSearchConfiguration}. La recherche hybride (mots-cles et
 * semantique) est activee par le service, sans index a configurer.
 */
@Component
public class KnowledgeBaseRetriever {

    /** Metadonnee posee sur chaque document (fichier .metadata.json). */
    static final String MODEL_ATTRIBUTE = "modele";
    /** Valeur portee par les documents transverses, par exemple les procedures de securite. */
    static final String ALL_MODELS = "Tous";

    private final BedrockAgentRuntimeClient client;
    private final TechAssistProperties props;

    public KnowledgeBaseRetriever(BedrockAgentRuntimeClient client, TechAssistProperties props) {
        this.client = client;
        this.props = props;
    }

    /**
     * @param question       question du technicien
     * @param equipmentModel modele d'equipement connu (ex : "Condensa 24"), ou null
     */
    public List<Passage> retrieve(String question, String equipmentModel) {
        ManagedSearchConfiguration.Builder search = ManagedSearchConfiguration.builder()
                .numberOfResults(props.maxResults());

        if (equipmentModel != null && !equipmentModel.isBlank()) {
            // Documentation du modele + documents transverses (securite, procedures).
            search.filter(RetrievalFilter.fromOrAll(List.of(
                    equalsModel(equipmentModel.trim()),
                    equalsModel(ALL_MODELS))));
        }

        RetrieveRequest request = RetrieveRequest.builder()
                .knowledgeBaseId(props.knowledgeBaseId())
                .retrievalQuery(KnowledgeBaseQuery.builder().text(question).build())
                .retrievalConfiguration(KnowledgeBaseRetrievalConfiguration.builder()
                        .managedSearchConfiguration(search.build())
                        .build())
                .build();

        return client.retrieve(request).retrievalResults().stream()
                .filter(r -> r.content() != null && r.content().text() != null)
                .map(KnowledgeBaseRetriever::toPassage)
                .toList();
    }

    private static RetrievalFilter equalsModel(String model) {
        return RetrievalFilter.fromEqualsValue(FilterAttribute.builder()
                .key(MODEL_ATTRIBUTE)
                .value(Document.fromString(model))
                .build());
    }

    private static Passage toPassage(KnowledgeBaseRetrievalResult r) {
        String uri = r.location() != null && r.location().s3Location() != null
                ? r.location().s3Location().uri()
                : null;
        Map<String, Document> meta = r.hasMetadata() ? r.metadata() : Map.of();
        return new Passage(r.content().text(), uri, r.score(), text(meta, "fabricant"), text(meta, MODEL_ATTRIBUTE));
    }

    private static String text(Map<String, Document> meta, String key) {
        Document d = meta.get(key);
        return d != null && d.isString() ? d.asString() : null;
    }
}
