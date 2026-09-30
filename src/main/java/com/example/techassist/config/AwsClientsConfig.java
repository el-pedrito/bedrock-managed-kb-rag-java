package com.example.techassist.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Clients AWS. Les credentials viennent de la chaine par defaut (role IAM de l'instance
 * EC2 ou de la tache ECS en production, profil local en developpement) : aucune cle
 * statique dans le code ni dans la configuration.
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

    @Bean(destroyMethod = "close")
    BedrockRuntimeClient bedrockRuntimeClient(TechAssistProperties props) {
        return BedrockRuntimeClient.builder()
                .region(Region.of(props.region()))
                .overrideConfiguration(c -> c
                        .retryStrategy(RetryMode.STANDARD)
                        .apiCallTimeout(Duration.ofSeconds(60)))
                .build();
    }
}
