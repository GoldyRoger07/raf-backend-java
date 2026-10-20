package com.raikiservice.raf.quiz.web;

import java.util.List;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.RewardService;
import com.raikiservice.raf.quiz.service.VoteService;
import com.raikiservice.raf.quiz.web.dto.CancelQuizDto;
import com.raikiservice.raf.quiz.web.dto.QuizDto;
import com.raikiservice.raf.quiz.web.dto.QuizResponse;
import com.raikiservice.raf.quiz.web.dto.RewardTierDto;
import com.raikiservice.raf.quiz.web.dto.SoloQuizDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/quizzes")
@RequiredArgsConstructor
@Tag(name = "Administration - Quiz")
public class AdminQuizController {

    private final QuizService quizService;
    private final RewardService rewardService;
    private final VoteService voteService;

    @PostMapping
    @Operation(summary = "Creer un quiz de groupe en brouillon")
    public ResponseEntity<QuizResponse> create(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody QuizDto request) {
        Long id = quizService.create(admin.getId(), request).getId();
        return ResponseEntity.status(HttpStatus.CREATED).body(quizService.view(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifier un quiz de groupe encore en brouillon")
    public QuizResponse update(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id, @Valid @RequestBody QuizDto request) {
        quizService.update(admin.getId(), id, request);
        return quizService.view(id);
    }

    @PostMapping("/solo")
    @Operation(summary = "Creer un quiz solo en brouillon")
    public ResponseEntity<QuizResponse> createSolo(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody SoloQuizDto request) {
        Long id = quizService.createSolo(admin.getId(), request).getId();
        return ResponseEntity.status(HttpStatus.CREATED).body(quizService.view(id));
    }

    @PutMapping("/solo/{id}")
    @Operation(summary = "Modifier un quiz solo encore en brouillon")
    public QuizResponse updateSolo(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id, @Valid @RequestBody SoloQuizDto request) {
        quizService.updateSolo(admin.getId(), id, request);
        return quizService.view(id);
    }

    @PostMapping("/{id}/duplicate")
    @Operation(summary = "Copier un quiz (quel que soit son etat) dans un nouveau brouillon")
    public ResponseEntity<QuizResponse> duplicate(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id) {
        Long copyId = quizService.duplicate(admin.getId(), id).getId();
        return ResponseEntity.status(HttpStatus.CREATED).body(quizService.view(copyId));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Fermer un quiz solo : plus aucune partie ne peut y etre lancee")
    public QuizResponse closeSolo(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id) {
        quizService.closeSolo(admin.getId(), id);
        return quizService.view(id);
    }

    @GetMapping
    @Operation(summary = "Lister les quiz par statut, et eventuellement par mode de jeu")
    public Page<QuizResponse> list(@RequestParam(defaultValue = "DRAFT") QuizStatus status,
            @RequestParam(required = false) GameMode mode,
            @PageableDefault(size = 20) Pageable pageable) {
        return quizService.viewByStatus(status, mode, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter un quiz")
    public QuizResponse get(@PathVariable Long id) {
        return quizService.view(id);
    }

    @PostMapping("/{id}/rewards")
    @Operation(summary = "Definir les tranches de recompense")
    public QuizResponse configureRewards(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id, @Valid @RequestBody List<RewardTierDto> tiers) {
        rewardService.configure(admin.getId(), quizService.require(id), tiers);
        return quizService.view(id);
    }

    /**
     * Bilan previsionnel : cagnotte totale, seuil de rentabilite, marge au seuil
     * minimum. A consulter avant de publier, puisque les montants sont fixes et
     * que seul le seuil de joueurs protege l'operation.
     */
    @GetMapping("/{id}/economics")
    @Operation(summary = "Bilan financier previsionnel du quiz")
    public RewardService.Economics economics(@PathVariable Long id) {
        return rewardService.economics(quizService.require(id));
    }

    @PostMapping("/{id}/topics")
    @Operation(summary = "Definir les sujets soumis au vote")
    public QuizResponse configureTopics(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id, @RequestBody List<Long> categoryIds) {
        quizService.configureTopics(admin.getId(), id, categoryIds);
        return quizService.view(id);
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "Publier le quiz : mise en vente des tickets (groupe) ou ouverture au jeu (solo)")
    public QuizResponse publish(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id,
            @RequestParam(required = false) Integer voteDurationMinutes) {
        quizService.publish(admin.getId(), id, voteDurationMinutes);
        return quizService.view(id);
    }

    @PostMapping("/{id}/close-vote")
    @Operation(summary = "Arreter le sujet : suivre le vote ou imposer un choix")
    public QuizResponse closeVote(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id,
            @RequestParam(required = false) Long overrideTopicId) {
        voteService.closeVote(admin.getId(), id, overrideTopicId);
        return quizService.view(id);
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "Demarrer manuellement un quiz programme")
    public QuizResponse start(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id) {
        quizService.start(admin.getId(), id);
        return quizService.view(id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Annuler le quiz et rembourser tous les tickets")
    public QuizResponse cancel(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id, @Valid @RequestBody CancelQuizDto request) {
        quizService.cancel(admin.getId(), id, request.reason());
        return quizService.view(id);
    }
}
