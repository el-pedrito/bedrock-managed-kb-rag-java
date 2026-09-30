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
 * Point d'entree REST, pense pour etre integre dans le backend existant de l'application
 * mobile. Il n'embarque pas d'authentification : il doit etre expose derriere
 * l'authentification du backend (voir README, section securite).
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
     * @param question       question du technicien
     * @param equipmentModel modele de l'equipement si connu (filtre la documentation)
     */
    public record AskRequest(
            @NotBlank @Size(max = 1000) String question,
            @Size(max = 100) String equipmentModel) { }
}
