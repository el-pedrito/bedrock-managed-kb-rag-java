package com.example.techassist.answer;

import com.example.techassist.config.TechAssistProperties;
import com.example.techassist.guard.GroundingGuard;
import com.example.techassist.kb.Passage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * "Classic LLM call" scenario, in three steps:
 * <ol>
 *   <li>Retrieval: {@code Retrieve} on the managed Knowledge Base, through a Spring AI {@link VectorStore}.</li>
 *   <li>Generation: Spring AI {@link ChatClient} (Bedrock Converse) with the domain prompt.</li>
 *   <li>Grounding check: {@code ApplyGuardrail} (AWS SDK), see {@link GroundingGuard}.</li>
 * </ol>
 *
 * <p>Retrieval is done explicitly (not through {@code QuestionAnswerAdvisor}) so that the exact
 * same passages are reused as the grounding source, and so that the model is not called when the
 * documentation contains nothing.
 */
@Service
public class AnswerService {

    // User-facing messages stay in French: the technicians are French speakers.
    static final String NOT_FOUND_MESSAGE = "Je ne trouve pas cette information dans la documentation disponible.";
    static final String BLOCKED_MESSAGE = "Je ne peux pas donner de réponse fiable à partir de la documentation disponible. "
            + "Reformulez la question ou précisez le modèle de l'équipement.";

    /** Metadata set on each document (.metadata.json file). "ALL" marks cross-model documents. */
    static final String MODEL_ATTRIBUTE = "model";
    static final String ALL_MODELS = "ALL";

    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

    private final VectorStore documentation;
    private final ChatClient chatClient;
    private final GroundingGuard guard;
    private final TechAssistProperties props;
    private final String systemPrompt;

    public AnswerService(VectorStore documentation, ChatClient chatClient, GroundingGuard guard,
            TechAssistProperties props, @Value("classpath:prompts/system-prompt.md") Resource systemPrompt) {
        this.documentation = documentation;
        this.chatClient = chatClient;
        this.guard = guard;
        this.props = props;
        this.systemPrompt = read(systemPrompt);
    }

    public Answer ask(String question, String equipmentModel) {
        long start = System.currentTimeMillis();

        // 1. Retrieval
        List<Passage> passages = documentation.similaritySearch(searchRequest(question, equipmentModel)).stream()
                .map(Passage::from)
                .toList();
        if (passages.isEmpty()) {
            return done(new Answer(NOT_FOUND_MESSAGE, Answer.Status.NOT_FOUND, List.of(),
                    usage(null, start, 0), null));
        }

        // 2. Generation
        String context = formatDocumentation(passages);
        ChatResponse response = chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage(context, question, equipmentModel))
                .call()
                .chatResponse();
        String text = Objects.requireNonNullElse(response.getResult().getOutput().getText(), "");

        // Exact refusal required by the prompt: nothing to check. An empty answer, on the other
        // hand, goes through the grounding check, which blocks it (it cannot be evaluated).
        if (text.strip().equals(NOT_FOUND_MESSAGE)) {
            return done(new Answer(NOT_FOUND_MESSAGE, Answer.Status.NOT_FOUND, List.of(),
                    usage(response, start, 0), null));
        }

        // 3. Grounding check: the answer is only shown if it is grounded in the passages.
        GroundingGuard.Verdict verdict = guard.check(context, question, text);
        Answer.Grounding grounding = new Answer.Grounding(verdict.grounding(), verdict.relevance());
        Answer.Usage usage = usage(response, start, verdict.textUnits());
        if (verdict.blocked()) {
            return done(new Answer(BLOCKED_MESSAGE, Answer.Status.BLOCKED, List.of(), usage, grounding));
        }
        return done(new Answer(text, Answer.Status.ANSWERED, toSources(passages), usage, grounding));
    }

    SearchRequest searchRequest(String question, String equipmentModel) {
        SearchRequest.Builder request = SearchRequest.builder().query(question).topK(props.maxResults());
        if (equipmentModel != null && !equipmentModel.isBlank()) {
            // Documentation of this model + cross-model documents (safety, procedures).
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            request.filterExpression(b.or(
                    b.eq(MODEL_ATTRIBUTE, equipmentModel.trim()),
                    b.eq(MODEL_ATTRIBUTE, ALL_MODELS)).build());
        }
        return request.build();
    }

    /** Documents first, question last: the layout recommended for Claude with retrieved content. */
    static String userMessage(String documents, String question, String equipmentModel) {
        String equipment = equipmentModel == null || equipmentModel.isBlank()
                ? ""
                : "Equipment model: " + asData(equipmentModel.trim()) + "\n";
        return "<documents>\n" + documents + "\n</documents>\n\n<question>\n"
                + equipment + asData(question) + "\n</question>";
    }

    /** One {@code <document>} per passage, with its index (used for citations), source and model. */
    static String formatDocumentation(List<Passage> passages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < passages.size(); i++) {
            Passage p = passages.get(i);
            sb.append("<document index=\"").append(i + 1).append("\">\n")
                    .append("<source>").append(asData(p.documentName())).append("</source>\n")
                    .append("<model>").append(p.model() == null ? "unknown" : asData(p.model())).append("</model>\n")
                    .append("<document_content>\n").append(asData(p.text())).append("\n</document_content>\n")
                    .append("</document>\n");
        }
        return sb.toString().strip();
    }

    /**
     * Neutralises angle brackets: a document or a question cannot close the {@code <documents>}
     * tag. The system prompt also states that this content is data.
     */
    static String asData(String text) {
        return text == null ? "" : text.replace("<", "‹").replace(">", "›");
    }

    private Answer.Usage usage(ChatResponse response, long start, int guardrailUnits) {
        Usage usage = response == null ? null : response.getMetadata().getUsage();
        int in = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int out = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        return new Answer.Usage(response == null ? null : props.modelId(), in, out, guardrailUnits,
                System.currentTimeMillis() - start);
    }

    /** One log line per question: status, consumption and scores (never the technician's text). */
    private static Answer done(Answer a) {
        log.info("question_answered status={} sources={} inputTokens={} outputTokens={} guardrailUnits={} "
                        + "groundingScore={} relevanceScore={} latencyMs={}",
                a.status(), a.sources().size(), a.usage().inputTokens(), a.usage().outputTokens(),
                a.usage().guardrailUnits(), a.grounding() == null ? null : a.grounding().groundingScore(),
                a.grounding() == null ? null : a.grounding().relevanceScore(), a.usage().latencyMs());
        return a;
    }

    private static List<Answer.Source> toSources(List<Passage> passages) {
        return IntStream.range(0, passages.size())
                .mapToObj(i -> new Answer.Source(i + 1, passages.get(i).documentName(),
                        passages.get(i).model(), passages.get(i).score()))
                .toList();
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("System prompt not found", e);
        }
    }
}
