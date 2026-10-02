package com.example.techassist.config;

import com.example.techassist.guard.GroundingGuard;
import com.example.techassist.kb.ManagedKnowledgeBaseVectorStore;
import java.time.Duration;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Cablage de l'approche hybride :
 * <ul>
 *   <li>Spring AI : {@link ChatClient} (generation) et {@link VectorStore} (abstraction de recherche).</li>
 *   <li>SDK AWS : {@code Retrieve} sur la Knowledge Base managee et {@code ApplyGuardrail}.</li>
 * </ul>
 * Les credentials viennent de la chaine par defaut (role IAM en production, profil local en
 * developpement) : aucune cle statique dans le code ni dans la configuration.
 */
@Configuration
public class AwsClientsConfig {

    @Bean(destroyMethod = "close")
    BedrockAgentRuntimeClient bedrockAgentRuntimeClient(TechAssistProperties props) {
        return BedrockAgentRuntimeClient.builder()
                .region(Region.of(props.region()))
                .overrideConfiguration(c -> c
                        .retryStrategy(RetryMode.STANDARD)
                        .apiCallTimeout(Duration.ofSeconds(15)))
                .build();
    }

    /**
     * Client bedrock-runtime unique : utilise par {@link GroundingGuard} (ApplyGuardrail) ET repris
     * par l'auto-configuration Spring AI pour Converse (elle consomme un BedrockRuntimeClient s'il
     * existe). D'ou un timeout dimensionne pour une generation complete.
     */
    @Bean(destroyMethod = "close")
    BedrockRuntimeClient bedrockRuntimeClient(TechAssistProperties props) {
        return BedrockRuntimeClient.builder()
                .region(Region.of(props.region()))
                .overrideConfiguration(c -> c
                        .retryStrategy(RetryMode.STANDARD)
                        .apiCallTimeout(Duration.ofSeconds(60)))
                .build();
    }

    @Bean
    VectorStore managedKnowledgeBaseVectorStore(BedrockAgentRuntimeClient client, TechAssistProperties props) {
        return new ManagedKnowledgeBaseVectorStore(client, props.knowledgeBaseId());
    }

    @Bean
    GroundingGuard groundingGuard(BedrockRuntimeClient bedrockRuntimeClient, TechAssistProperties props) {
        return new GroundingGuard(bedrockRuntimeClient, props.guardrailId(), props.guardrailVersion());
    }

    /** ChatClient configure par cette application : modele EU, temperature 0, plafond de tokens. */
    @Bean
    ChatClient chatClient(ChatClient.Builder builder, TechAssistProperties props) {
        return builder
                .defaultOptions(BedrockChatOptions.builder()
                        .model(props.modelId())
                        .temperature(0.0)
                        .maxTokens(props.maxTokens()))
                .build();
    }
}
