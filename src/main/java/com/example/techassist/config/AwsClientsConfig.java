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
 * Wiring of the hybrid approach:
 * <ul>
 *   <li>Spring AI: {@link ChatClient} (generation) and {@link VectorStore} (retrieval abstraction).</li>
 *   <li>AWS SDK: {@code Retrieve} on the managed Knowledge Base, and {@code ApplyGuardrail}.</li>
 * </ul>
 * Credentials come from the default chain (IAM role in production, local profile in development):
 * no static key in the code or in the configuration.
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
     * Single bedrock-runtime client: used by {@link GroundingGuard} (ApplyGuardrail) AND picked up by
     * the Spring AI auto-configuration for Converse (it consumes an existing BedrockRuntimeClient).
     * Hence a timeout sized for a full generation.
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

    /** ChatClient configured for this application: EU model, temperature 0, token cap. */
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
