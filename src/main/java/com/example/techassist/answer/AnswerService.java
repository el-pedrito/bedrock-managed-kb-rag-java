package com.example.techassist.answer;

import com.example.techassist.config.TechAssistProperties;
import com.example.techassist.guard.GroundingGuard;
import com.example.techassist.kb.Passage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Scenario "appel LLM classique" : un {@code Retrieve} sur la Knowledge Base managee, un appel au
 * modele, puis un controle d'ancrage.
 *
 * <ul>
 *   <li>Recherche : {@link VectorStore} Spring AI, implemente sur la Knowledge Base managee.</li>
 *   <li>Generation : {@link ChatClient} Spring AI sur Bedrock Converse.</li>
 *   <li>Controle d'ancrage : {@code ApplyGuardrail} (SDK AWS), voir {@link GroundingGuard}.</li>
 * </ul>
 *
 * <p>La recherche est faite explicitement (et non par {@code QuestionAnswerAdvisor}) pour deux
 * raisons : ne pas appeler le modele quand la documentation ne contient rien, et reutiliser les
 * memes passages comme source de verite du controle d'ancrage.
 */
@Service
public class AnswerService {

    static final String NOT_FOUND_MESSAGE = "Je ne trouve pas cette information dans la documentation disponible.";
    static final String BLOCKED_MESSAGE = "Je ne peux pas donner de réponse fiable à partir de la documentation disponible. "
            + "Reformulez la question ou précisez le modèle de l'équipement.";

    /** Metadonnee posee sur chaque document (fichier .metadata.json). */
    static final String MODEL_ATTRIBUTE = "modele";
    /** Valeur portee par les documents transverses, par exemple les procedures de securite. */
    static final String ALL_MODELS = "Tous";

    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

    private final VectorStore documentation;
    private final ChatClient chatClient;
    private final GroundingGuard guard;
    private final TechAssistProperties props;
    private final String systemPrompt;

    public AnswerService(VectorStore documentation, ChatClient chatClient, GroundingGuard guard,
            TechAssistProperties props, @Value("classpath:prompts/system-prompt.md") Resource systemPromptResource) {
        this.documentation = documentation;
        this.chatClient = chatClient;
        this.guard = guard;
        this.props = props;
        this.systemPrompt = read(systemPromptResource);
    }

    public Answer ask(String question, String equipmentModel) {
        long start = System.currentTimeMillis();

        List<Passage> passages = documentation.similaritySearch(searchRequest(question, equipmentModel)).stream()
                .map(Passage::from)
                .toList();

        if (passages.isEmpty()) {
            long latency = System.currentTimeMillis() - start;
            log.info("question_answered status=NOT_FOUND passages=0 latencyMs={}", latency);
            return new Answer(NOT_FOUND_MESSAGE, Answer.Status.NOT_FOUND, List.of(),
                    new Answer.Usage(null, 0, 0, 0, latency), null);
        }

        String context = formatDocumentation(passages);
        String userMessage = "<documentation>\n" + context + "\n</documentation>\n\n<question>\n"
                + (equipmentModel == null || equipmentModel.isBlank()
                        ? ""
                        : "Équipement : " + asData(equipmentModel.trim()) + "\n")
                + asData(question) + "\n</question>";

        ChatResponse response = chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .chatResponse();
        String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? null
                : response.getResult().getOutput().getText();

        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        int in = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int out = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;

        // Refus impose par le prompt (phrase exacte) : remonte comme NOT_FOUND, sans payer le
        // controle d'ancrage. Seule la phrase exacte est acceptee : un refus suivi d'autre
        // contenu passe par le controle comme une reponse normale.
        if (text != null && text.strip().equals(NOT_FOUND_MESSAGE)) {
            long latency = System.currentTimeMillis() - start;
            log.info("question_answered status=NOT_FOUND passages={} model={} inputTokens={} outputTokens={} latencyMs={}",
                    passages.size(), props.modelId(), in, out, latency);
            return new Answer(NOT_FOUND_MESSAGE, Answer.Status.NOT_FOUND, List.of(),
                    new Answer.Usage(props.modelId(), in, out, 0, latency), null);
        }

        // Query du controle d'ancrage = la question seule (limite de 1 000 caracteres).
        GroundingGuard.Verdict verdict;
        if (!props.guardrailEnabled()) {
            verdict = GroundingGuard.Verdict.DISABLED;
        }
        else if (text == null || text.isBlank()) {
            verdict = GroundingGuard.Verdict.NOT_EVALUATED;
        }
        else {
            verdict = guard.check(context, question, text);
        }
        boolean blocked = verdict.blocked() || text == null || text.isBlank();
        long latency = System.currentTimeMillis() - start;

        log.info("question_answered status={} passages={} model={} inputTokens={} outputTokens={} "
                        + "groundingScore={} relevanceScore={} guardrailUnits={} latencyMs={}",
                blocked ? "BLOCKED" : "ANSWERED", passages.size(), props.modelId(), in, out,
                verdict.grounding(), verdict.relevance(), verdict.textUnits(), latency);

        Answer.Grounding grounding = props.guardrailEnabled()
                ? new Answer.Grounding(verdict.grounding(), verdict.relevance())
                : null;
        Answer.Usage answerUsage = new Answer.Usage(props.modelId(), in, out, verdict.textUnits(), latency);
        if (blocked) {
            return new Answer(BLOCKED_MESSAGE, Answer.Status.BLOCKED, List.of(), answerUsage, grounding);
        }
        // Refus suivi d'une explication : controle comme une reponse (fail-closed), puis remonte
        // en NOT_FOUND, sans sources, pour que l'application le distingue d'une vraie reponse.
        if (text.strip().startsWith(NOT_FOUND_MESSAGE)) {
            return new Answer(text, Answer.Status.NOT_FOUND, List.of(), answerUsage, grounding);
        }
        return new Answer(text, Answer.Status.ANSWERED, toSources(passages), answerUsage, grounding);
    }

    SearchRequest searchRequest(String question, String equipmentModel) {
        SearchRequest.Builder request = SearchRequest.builder().query(question).topK(props.maxResults());
        if (equipmentModel != null && !equipmentModel.isBlank()) {
            // Documentation du modele + documents transverses (securite, procedures).
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            Filter.Expression filter = b.or(
                    b.eq(MODEL_ATTRIBUTE, equipmentModel.trim()),
                    b.eq(MODEL_ATTRIBUTE, ALL_MODELS)).build();
            request.filterExpression(filter);
        }
        return request.build();
    }

    static String formatDocumentation(List<Passage> passages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < passages.size(); i++) {
            Passage p = passages.get(i);
            sb.append("[").append(i + 1).append("] ").append(asData(p.documentName()));
            if (p.modele() != null) {
                sb.append(" (modèle : ").append(asData(p.modele())).append(")");
            }
            sb.append("\n").append(asData(p.text())).append("\n\n");
        }
        return sb.toString().strip();
    }

    /**
     * Neutralise les chevrons : un document ou une question ne peut pas fermer la balise
     * {@code <documentation>} ni ouvrir une fausse section d'instructions. Le prompt systeme
     * precise en plus que ce contenu est une donnee, jamais une instruction.
     */
    static String asData(String text) {
        return text == null ? "" : text.replace("<", "‹").replace(">", "›");
    }

    private static List<Answer.Source> toSources(List<Passage> passages) {
        return IntStream.range(0, passages.size())
                .mapToObj(i -> new Answer.Source(i + 1, passages.get(i).documentName(),
                        passages.get(i).modele(), passages.get(i).score()))
                .toList();
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
