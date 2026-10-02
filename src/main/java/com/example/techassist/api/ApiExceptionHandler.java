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
 * Turns errors into clean HTTP responses, without exposing internal details to the client.
 *
 * <p>AWS errors can arrive as is (direct SDK calls: Retrieve, ApplyGuardrail) or wrapped by
 * Spring AI (Converse through ChatClient, {@code TransientAiException} /
 * {@code NonTransientAiException}). So we look for the AWS error along the whole cause chain:
 * the HTTP status does not depend on the layer that raised the exception.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // Error details stay in French: they are shown to French-speaking technicians.
    @ExceptionHandler(RuntimeException.class)
    ProblemDetail handle(RuntimeException e) {
        // HTTP errors already qualified by Spring (404, 405...): keep the original status.
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

    /** First AWS error in the cause chain (bounded, in case of a cycle). */
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

    /** Throttling of Converse/ApplyGuardrail (bedrock-runtime) or of Retrieve (bedrock-agent-runtime). */
    private static boolean isThrottling(SdkException e) {
        return e instanceof software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException
                || e instanceof software.amazon.awssdk.services.bedrockagentruntime.model.ThrottlingException
                || (e instanceof AwsServiceException service && service.isThrottlingException());
    }
}
