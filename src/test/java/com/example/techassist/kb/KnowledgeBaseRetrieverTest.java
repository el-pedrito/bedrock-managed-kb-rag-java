package com.example.techassist.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.techassist.config.TechAssistProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalResultContent;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalResultLocation;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalResultS3Location;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveResponse;

class KnowledgeBaseRetrieverTest {

    private final BedrockAgentRuntimeClient client = mock(BedrockAgentRuntimeClient.class);
    private final TechAssistProperties props = new TechAssistProperties(
            "eu-west-1", "KB123", "eu.model", null, null, 5, 800);
    private final KnowledgeBaseRetriever retriever = new KnowledgeBaseRetriever(client, props);

    @Test
    void usesManagedSearchConfigurationAndNeverVectorSearch() {
        when(client.retrieve(any(RetrieveRequest.class))).thenReturn(RetrieveResponse.builder().build());

        retriever.retrieve("F28 sur la chaudière", null);

        RetrieveRequest sent = captureRequest();
        assertThat(sent.knowledgeBaseId()).isEqualTo("KB123");
        assertThat(sent.retrievalConfiguration().vectorSearchConfiguration()).isNull();
        assertThat(sent.retrievalConfiguration().managedSearchConfiguration().numberOfResults()).isEqualTo(5);
        assertThat(sent.retrievalConfiguration().managedSearchConfiguration().filter()).isNull();
    }

    @Test
    void filtersOnModelAndKeepsCrossModelDocuments() {
        when(client.retrieve(any(RetrieveRequest.class))).thenReturn(RetrieveResponse.builder().build());

        retriever.retrieve("F28", "Condensa 24");

        var filter = captureRequest().retrievalConfiguration().managedSearchConfiguration().filter();
        assertThat(filter.orAll()).hasSize(2);
        assertThat(filter.orAll().get(0).equalsValue().value().asString()).isEqualTo("Condensa 24");
        assertThat(filter.orAll().get(1).equalsValue().value().asString()).isEqualTo("Tous");
    }

    @Test
    void mapsResultsToPassages() {
        KnowledgeBaseRetrievalResult result = KnowledgeBaseRetrievalResult.builder()
                .content(RetrievalResultContent.builder().text("F28 : pression trop basse").build())
                .location(RetrievalResultLocation.builder()
                        .s3Location(RetrievalResultS3Location.builder()
                                .uri("s3://bucket/thermalys/notice.md").build())
                        .build())
                .metadata(Map.of("modele", Document.fromString("Condensa 24"),
                        "fabricant", Document.fromString("Thermalys")))
                .score(0.82)
                .build();
        when(client.retrieve(any(RetrieveRequest.class)))
                .thenReturn(RetrieveResponse.builder().retrievalResults(result).build());

        List<Passage> passages = retriever.retrieve("F28", null);

        assertThat(passages).singleElement().satisfies(p -> {
            assertThat(p.documentName()).isEqualTo("notice.md");
            assertThat(p.modele()).isEqualTo("Condensa 24");
            assertThat(p.fabricant()).isEqualTo("Thermalys");
            assertThat(p.score()).isEqualTo(0.82);
        });
    }

    private RetrieveRequest captureRequest() {
        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(client).retrieve(captor.capture());
        return captor.getValue();
    }
}
