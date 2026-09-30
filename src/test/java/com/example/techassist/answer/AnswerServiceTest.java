package com.example.techassist.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.techassist.config.TechAssistProperties;
import com.example.techassist.kb.KnowledgeBaseRetriever;
import com.example.techassist.kb.Passage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailConverseContentQualifier;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

class AnswerServiceTest {

    private final KnowledgeBaseRetriever retriever = mock(KnowledgeBaseRetriever.class);
    private final BedrockRuntimeClient bedrock = mock(BedrockRuntimeClient.class);

    private static final Passage PASSAGE = new Passage("F28 : pression d'eau trop basse, remettre à 1,2 bar",
            "s3://bucket/thermalys/notice.md", 0.8, "Thermalys", "Condensa 24");

    @Test
    void doesNotCallTheModelWhenNothingIsFound() {
        when(retriever.retrieve(anyString(), isNull())).thenReturn(List.of());

        Answer answer = service(null).ask("Couple de serrage du brûleur ?", null);

        assertThat(answer.status()).isEqualTo(Answer.Status.NOT_FOUND);
        assertThat(answer.answer()).isEqualTo(AnswerService.NOT_FOUND_MESSAGE);
        verify(bedrock, never()).converse(any(ConverseRequest.class));
    }

    @Test
    void answersWithSourcesAndUsage() {
        when(retriever.retrieve(anyString(), isNull())).thenReturn(List.of(PASSAGE));
        when(bedrock.converse(any(ConverseRequest.class))).thenReturn(modelReply("Pression trop basse [1]", StopReason.END_TURN));

        Answer answer = service(null).ask("Que signifie F28 ?", null);

        assertThat(answer.status()).isEqualTo(Answer.Status.ANSWERED);
        assertThat(answer.answer()).isEqualTo("Pression trop basse [1]");
        assertThat(answer.sources()).singleElement().satisfies(s -> {
            assertThat(s.index()).isEqualTo(1);
            assertThat(s.document()).isEqualTo("notice.md");
        });
        assertThat(answer.usage().inputTokens()).isEqualTo(900);
        assertThat(answer.usage().outputTokens()).isEqualTo(60);
    }

    @Test
    void returnsSafeMessageWhenGuardrailBlocks() {
        when(retriever.retrieve(anyString(), isNull())).thenReturn(List.of(PASSAGE));
        when(bedrock.converse(any(ConverseRequest.class))).thenReturn(modelReply("texte non fondé", StopReason.GUARDRAIL_INTERVENED));

        Answer answer = service("gr-123").ask("Que signifie F28 ?", null);

        assertThat(answer.status()).isEqualTo(Answer.Status.BLOCKED);
        assertThat(answer.answer()).isEqualTo(AnswerService.BLOCKED_MESSAGE);
        assertThat(answer.sources()).isEmpty();
    }

    @Test
    void marksDocumentationAsGroundingSourceWhenGuardrailIsOn() {
        ConverseRequest request = service("gr-123").buildRequest("Que signifie F28 ?", "Condensa 24", List.of(PASSAGE));

        List<ContentBlock> content = request.messages().get(0).content();
        assertThat(content).hasSize(2);
        assertThat(content.get(0).guardContent().text().qualifiers())
                .containsExactly(GuardrailConverseContentQualifier.GROUNDING_SOURCE);
        assertThat(content.get(1).guardContent().text().qualifiers())
                .containsExactly(GuardrailConverseContentQualifier.QUERY);
        assertThat(content.get(1).guardContent().text().text()).contains("Condensa 24");
        assertThat(request.guardrailConfig().guardrailIdentifier()).isEqualTo("gr-123");
        assertThat(request.inferenceConfig().temperature()).isZero();
    }

    @Test
    void numbersPassagesForCitations() {
        String doc = AnswerService.formatDocumentation(List.of(PASSAGE, PASSAGE));
        assertThat(doc).startsWith("[1] notice.md (modèle : Condensa 24)").contains("[2] notice.md");
    }

    private AnswerService service(String guardrailId) {
        TechAssistProperties props = new TechAssistProperties("eu-west-1", "KB123",
                "eu.anthropic.claude-haiku-4-5-20251001-v1:0", guardrailId, guardrailId == null ? null : "1", 5, 800);
        return new AnswerService(retriever, bedrock, props, new ClassPathResource("prompts/system-prompt.md"));
    }

    private static ConverseResponse modelReply(String text, StopReason stopReason) {
        return ConverseResponse.builder()
                .output(ConverseOutput.fromMessage(Message.builder()
                        .role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText(text))
                        .build()))
                .stopReason(stopReason)
                .usage(TokenUsage.builder().inputTokens(900).outputTokens(60).totalTokens(960).build())
                .build();
    }
}
