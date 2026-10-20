package com.raikiservice.raf.quiz.web;

import java.util.List;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.TicketService;
import com.raikiservice.raf.quiz.service.VoteService;
import com.raikiservice.raf.quiz.web.dto.PurchaseTicketDto;
import com.raikiservice.raf.quiz.web.dto.QuizResponse;
import com.raikiservice.raf.quiz.web.dto.TicketResponse;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Vue joueur des quiz : consultation, achat de ticket, vote du sujet. */
@RestController
@RequestMapping("/api/quizzes")
@RequiredArgsConstructor
@Tag(name = "Quiz")
public class QuizController {

    private final QuizService quizService;
    private final TicketService ticketService;
    private final VoteService voteService;

    @GetMapping
    @Operation(summary = "Lister les quiz visibles d'un mode de jeu (En groupe par defaut)")
    public Page<QuizResponse> list(@RequestParam(defaultValue = "GROUP") GameMode mode,
            @PageableDefault(size = 20) Pageable pageable) {
        return quizService.viewVisible(mode, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter un quiz")
    public QuizResponse get(@PathVariable Long id) {
        return quizService.view(id);
    }

    @PostMapping("/{id}/tickets")
    @Operation(summary = "Acheter un ticket (1 ticket = 1 partie)")
    public ResponseEntity<TicketResponse> purchase(@AuthenticationPrincipal UserPrincipal player,
            @PathVariable Long id, @Valid @RequestBody PurchaseTicketDto request) {

        TicketResponse response = ticketService.purchase(
                player.getId(), id, request.idempotencyKey());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}/tickets")
    @Operation(summary = "Mes tickets pour ce quiz")
    public List<TicketResponse> myTickets(@AuthenticationPrincipal UserPrincipal player,
            @PathVariable Long id) {
        return ticketService.myTicketsForQuiz(player.getId(), id);
    }

    @PostMapping("/{id}/vote/{topicId}")
    @Operation(summary = "Voter pour un sujet (detenteurs de ticket uniquement)")
    public QuizResponse vote(@AuthenticationPrincipal UserPrincipal player,
            @PathVariable Long id, @PathVariable Long topicId) {
        voteService.vote(player.getId(), id, topicId);
        return quizService.view(id);
    }
}
