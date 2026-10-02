package com.example.techassist.answer;

import java.util.List;

/**
 * Answer returned to the mobile app.
 *
 * @param answer     answer text
 * @param status     ANSWERED, NOT_FOUND (nothing in the documentation) or BLOCKED (grounding check)
 * @param sources    passages used, numbered as in the answer
 * @param usage      consumption, to track the cost per question
 * @param grounding  grounding check scores (null when no check was run)
 */
public record Answer(String answer, Status status, List<Source> sources, Usage usage, Grounding grounding) {

    public enum Status { ANSWERED, NOT_FOUND, BLOCKED }

    public record Source(int index, String document, String model, Double score) { }

    /**
     * @param modelId         model used (null if the model was not called)
     * @param inputTokens     input tokens
     * @param outputTokens    output tokens
     * @param guardrailUnits  text units billed by the grounding check
     * @param latencyMs       total server-side processing time
     */
    public record Usage(String modelId, int inputTokens, int outputTokens, int guardrailUnits, long latencyMs) { }

    /**
     * @param groundingScore  is the answer grounded in the documentation (0 to 1)
     * @param relevanceScore  does the answer address the question (0 to 1)
     */
    public record Grounding(Double groundingScore, Double relevanceScore) { }
}
