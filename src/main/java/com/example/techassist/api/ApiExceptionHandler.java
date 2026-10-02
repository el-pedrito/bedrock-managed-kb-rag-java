package com.example.techassist.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Traduit les erreurs en reponses HTTP propres, sans exposer le detail interne au client.
 *
 * <p>Les erreurs AWS peuvent arriver telles quelles (appel SDK direct : Retrieve, ApplyGuardrail)
 * ou enveloppees par Spring AI (Converse via ChatClient, {@code TransientAiException} /
 * {@code NonTransientAiException}). On cherche donc l'erreur AWS dans toute la chaine des causes :
 * le statut HTTP ne depend pas de la couche qui a leve l'exception.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RuntimeException.class)
    ProblemDetail handle(RuntimeException e) {
        // Erreurs HTTP deja qualifiees par Spring (404, 405...) : statut d'origine conserve.
        if (e instanceof ErrorResponse response) {
            return response.getBody();
        }
        SdkException aws = awsCause(e);
        if (aws != null && isThrottling(aws)) {
            log.warn("bedrock_throttled requestId={}",
                    aws instanceof AwsServiceException service ? service.requestId() : null);
            return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                    "Service temporairement saturé, réessayez dans quelques secondes.");
        }
        if (aws != null) {
            log.error("aws_call_failed error={}", aws.getClass().getSimpleName(), e);
            return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                    "L'assistant est momentanément indisponible.");
        }
        log.error("unexpected_error error={}", e.getClass().getSimpleName(), e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur interne.");
    }

    /** Premiere erreur AWS dans la chaine des causes (bornee, en cas de cycle). */
    static SdkException awsCause(Throwable e) {
        Throwable current = e;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof SdkException sdk) {
                return sdk;
            }
            current = current.getCause();
        }
        return null;
    }

    /** Throttling de Converse/ApplyGuardrail (bedrock-runtime) ou de Retrieve (bedrock-agent-runtime). */
    private static boolean isThrottling(SdkException e) {
        return e instanceof software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException
                || e instanceof software.amazon.awssdk.services.bedrockagentruntime.model.ThrottlingException
                || (e instanceof AwsServiceException service && service.isThrottlingException());
    }
}
