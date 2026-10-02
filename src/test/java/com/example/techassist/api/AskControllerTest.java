package com.example.techassist.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.techassist.answer.Answer;
import com.example.techassist.answer.AnswerService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class AskControllerTest {

    private final AnswerService answerService = mock(AnswerService.class);
    private final MockMvc mvc = mockMvc();

    @Test
    void returnsTheAnswer() throws Exception {
        when(answerService.ask(anyString(), eq("Condensa 24"))).thenReturn(new Answer("Pression trop basse [1]",
                Answer.Status.ANSWERED, List.of(new Answer.Source(1, "notice.md", "Condensa 24", 0.8)),
                new Answer.Usage("eu.model", 900, 60, 2, 1200), new Answer.Grounding(0.93, 0.88)));

        mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Que signifie F28 ?\",\"equipmentModel\":\"Condensa 24\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.sources[0].document").value("notice.md"));
    }

    @Test
    void rejectsAnEmptyQuestion() throws Exception {
        mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void throttlingWrappedBySpringAiIsStillA429() throws Exception {
        var throttled = software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException.builder()
                .message("Too many requests").build();
        when(answerService.ask(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.springframework.ai.retry.TransientAiException("converse failed", throttled));

        mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"F28 ?\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void awsFailureWrappedBySpringAiIsA502WithoutDetail() throws Exception {
        var failure = software.amazon.awssdk.core.exception.SdkClientException.create("arn:aws:secret-detail");
        when(answerService.ask(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException(new org.springframework.ai.retry.NonTransientAiException("x", failure)));

        mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"F28 ?\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("arn"))));
    }

    @Test
    void unexpectedErrorIsA500WithoutDetail() throws Exception {
        when(answerService.ask(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("arn:aws:secret-detail"));

        mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"F28 ?\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("arn"))));
    }

    private MockMvc mockMvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(new AskController(answerService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .build();
    }
}
