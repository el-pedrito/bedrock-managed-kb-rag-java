package com.example.techassist.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;

/**
 * Traduit les erreurs AWS en reponses HTTP propres, sans exposer le detail interne au client.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ThrottlingException.class)
    ProblemDetail throttled(ThrottlingException e) {
        log.warn("bedrock_throttled requestId={}", e.requestId());
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Service temporairement saturé, réessayez dans quelques secondes.");
    }

    @ExceptionHandler(SdkException.class)
    ProblemDetail awsError(SdkException e) {
        log.error("aws_call_failed", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "L'assistant est momentanément indisponible.");
    }
}
