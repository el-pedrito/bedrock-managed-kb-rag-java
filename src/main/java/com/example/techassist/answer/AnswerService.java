package com.example.techassist.answer;

import com.example.techassist.config.TechAssistProperties;
import com.example.techassist.kb.KnowledgeBaseRetriever;
import com.example.techassist.kb.Passage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailConverseContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailConverseContentQualifier;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailConverseTextBlock;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

/**
 * Scenario "appel LLM classique" : un Retrieve sur la Knowledge Base managee, puis un Converse.
 *
 * <p>Le flux est deterministe : toujours exactement 1 recherche et au plus 1 appel au modele.
 * Si la recherche ne renvoie rien, le modele n'est pas appele du tout (pas de cout, pas de
 * risque d'hallucination).
 */
@Service
public class AnswerService {

    static final String NOT_FOUND_MESSAGE = "Je ne trouve pas cette information dans la documentation disponible.";
    static final String BLOCKED_MESSAGE = "Je ne peux pas donner de réponse fiable à partir de la documentation disponible. "
            + "Reformulez la question ou précisez le modèle de l'équipement.";

    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

    private final KnowledgeBaseRetriever retriever;
    private final BedrockRuntimeClient bedrock;
    private final TechAssistProperties props;
    private final String systemPrompt;

    public AnswerService(KnowledgeBaseRetriever retriever, BedrockRuntimeClient bedrock, TechAssistProperties props,
            @Value("classpath:prompts/system-prompt.md") Resource systemPromptResource) {
        this.retriever = retriever;
        this.bedrock = bedrock;
        this.props = props;
        this.systemPrompt = read(systemPromptResource);
    }

    public Answer ask(String question, String equipmentModel) {
        long start = System.currentTimeMillis();

        List<Passage> passages = retriever.retrieve(question, equipmentModel);
        List<Answer.Source> sources = toSources(passages);

        if (passages.isEmpty()) {
            long latency = System.currentTimeMillis() - start;
            log.info("question_answered status=NOT_FOUND passages=0 latencyMs={}", latency);
            return new Answer(NOT_FOUND_MESSAGE, Answer.Status.NOT_FOUND, List.of(),
                    new Answer.Usage(null, 0, 0, latency));
        }

        ConverseResponse response = bedrock.converse(buildRequest(question, equipmentModel, passages));

        boolean blocked = response.stopReason() == StopReason.GUARDRAIL_INTERVENED;
        String text = blocked ? BLOCKED_MESSAGE : firstText(response);
        long latency = System.currentTimeMillis() - start;
        int in = response.usage() != null ? response.usage().inputTokens() : 0;
        int out = response.usage() != null ? response.usage().outputTokens() : 0;

        log.info("question_answered status={} passages={} model={} inputTokens={} outputTokens={} latencyMs={}",
                blocked ? "BLOCKED" : "ANSWERED", passages.size(), props.modelId(), in, out, latency);

        return new Answer(text, blocked ? Answer.Status.BLOCKED : Answer.Status.ANSWERED,
                blocked ? List.of() : sources, new Answer.Usage(props.modelId(), in, out, latency));
    }

    ConverseRequest buildRequest(String question, String equipmentModel, List<Passage> passages) {
        String documentation = formatDocumentation(passages);
        String userQuestion = equipmentModel == null || equipmentModel.isBlank()
                ? question
                : "Équipement : " + equipmentModel.trim() + "\nQuestion : " + question;

        List<ContentBlock> content = new ArrayList<>();
        if (props.guardrailEnabled()) {
            // Controle d'ancrage : le garde-fou verifie que la reponse est fondee sur la
            // documentation (grounding_source) et pertinente pour la question (query).
            content.add(guarded("<documentation>\n" + documentation + "\n</documentation>",
                    GuardrailConverseContentQualifier.GROUNDING_SOURCE));
            content.add(guarded(userQuestion, GuardrailConverseContentQualifier.QUERY));
        }
        else {
            content.add(ContentBlock.fromText("<documentation>\n" + documentation + "\n</documentation>"));
            content.add(ContentBlock.fromText(userQuestion));
        }

        ConverseRequest.Builder request = ConverseRequest.builder()
                .modelId(props.modelId())
                .system(SystemContentBlock.fromText(systemPrompt))
                .messages(Message.builder().role(ConversationRole.USER).content(content).build())
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(props.maxTokens())
                        .temperature(0f)
                        .build());

        if (props.guardrailEnabled()) {
            request.guardrailConfig(GuardrailConfiguration.builder()
                    .guardrailIdentifier(props.guardrailId())
                    .guardrailVersion(props.guardrailVersion())
                    .build());
        }
        return request.build();
    }

    static String formatDocumentation(List<Passage> passages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < passages.size(); i++) {
            Passage p = passages.get(i);
            sb.append("[").append(i + 1).append("] ").append(p.documentName());
            if (p.modele() != null) {
                sb.append(" (modèle : ").append(p.modele()).append(")");
            }
            sb.append("\n").append(p.text()).append("\n\n");
        }
        return sb.toString().strip();
    }

    private static List<Answer.Source> toSources(List<Passage> passages) {
        return IntStream.range(0, passages.size())
                .mapToObj(i -> new Answer.Source(i + 1, passages.get(i).documentName(),
                        passages.get(i).modele(), passages.get(i).score()))
                .toList();
    }

    private static ContentBlock guarded(String text, GuardrailConverseContentQualifier qualifier) {
        return ContentBlock.fromGuardContent(GuardrailConverseContentBlock.fromText(
                GuardrailConverseTextBlock.builder().text(text).qualifiers(qualifier).build()));
    }

    private static String firstText(ConverseResponse response) {
        return response.output().message().content().stream()
                .filter(c -> c.text() != null)
                .map(ContentBlock::text)
                .findFirst()
                .orElse(NOT_FOUND_MESSAGE);
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("System prompt introuvable", e);
        }
    }
}
