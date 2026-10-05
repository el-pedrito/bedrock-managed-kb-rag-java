package com.example.techassist.api;

import com.example.techassist.answer.Answer;
import com.example.techassist.answer.AnswerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry point, designed to be plugged into the existing mobile app backend. It carries no
 * authentication: it must be exposed behind the backend authentication (see README, security).
 */
@RestController
@RequestMapping("/api")
public class AskController {

    private final AnswerService answerService;

    public AskController(AnswerService answerService) {
        this.answerService = answerService;
    }

    @PostMapping("/ask")
    public Answer ask(@Valid @RequestBody AskRequest request) {
        return answerService.ask(request.question(), request.equipmentModel());
    }

    /**
     * @param question       the technician's question
     * @param equipmentModel equipment model if known (filters the documentation)
     */
    public record AskRequest(
            @NotBlank @Size(max = 1000) String question,
            @Size(max = 100) String equipmentModel) { }
}
