package com.raikiservice.raf.practice.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.practice.service.PracticeService;
import com.raikiservice.raf.practice.service.PracticeService.AnswerResult;
import com.raikiservice.raf.practice.service.PracticeService.Overview;
import com.raikiservice.raf.practice.service.PracticeService.SessionView;
import com.raikiservice.raf.practice.service.ProgressService;
import com.raikiservice.raf.practice.service.ProgressService.Progress;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Entrainement gratuit et progression du joueur connecte (XP, niveau, missions, badges). */
@RestController
@RequiredArgsConstructor
@Tag(name = "Jeu")
public class PracticeController {

    private final PracticeService practiceService;
    private final ProgressService progressService;

    @GetMapping("/api/me/progress")
    @Operation(summary = "Progression : XP, niveau, missions du jour, badges, serie de jours")
    public Progress progress(@AuthenticationPrincipal UserPrincipal principal) {
        return progressService.of(principal.getId());
    }

    @GetMapping("/api/practice")
    @Operation(summary = "Entrainement : sujets proposes et parties restantes aujourd'hui")
    public Overview overview(@AuthenticationPrincipal UserPrincipal principal) {
        return practiceService.overview(principal.getId());
    }

    @PostMapping("/api/practice/sessions")
    @Operation(summary = "Lancer un entrainement sur un sujet (ou reprendre celui en cours)")
    public SessionView start(@AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody StartDto request) {
        return practiceService.start(principal.getId(), request.categoryId());
    }

    @GetMapping("/api/practice/sessions/{sessionId}")
    @Operation(summary = "Un entrainement du joueur, corrections des questions repondues comprises")
    public SessionView get(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long sessionId) {
        return practiceService.get(principal.getId(), sessionId);
    }

    @PostMapping("/api/practice/sessions/{sessionId}/answers")
    @Operation(summary = "Repondre a une question : la correction et l'explication reviennent aussitot",
            description = "choiceId vide : temps ecoule, la question compte comme fausse.")
    public AnswerResult answer(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long sessionId,
            @Valid @RequestBody AnswerDto request) {
        return practiceService.answer(principal.getId(), sessionId, request.questionId(), request.choiceId());
    }

    public record StartDto(@NotNull Long categoryId) {
    }

    public record AnswerDto(@NotNull Long questionId, Long choiceId) {
    }
}
