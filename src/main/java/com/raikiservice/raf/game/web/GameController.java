package com.raikiservice.raf.game.web;

import java.util.List;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.common.util.RequestContext;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameOutcomeResponse;
import com.raikiservice.raf.game.web.dto.GameResultResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.GameTurnResponse;
import com.raikiservice.raf.game.web.dto.StartGameDto;
import com.raikiservice.raf.game.web.dto.SubmitGameDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

/** Parties de jeu : lancement, reponses, historique et corrige. */
@RestController
@RequestMapping("/api/game")
@RequiredArgsConstructor
@Tag(name = "Jeu")
public class GameController {

    private final GameService gameService;

    /**
     * 201 pour une nouvelle partie (un ticket vient d'etre consomme), 200 pour la
     * reprise d'une partie en cours.
     */
    @PostMapping("/start")
    @Operation(summary = "Lancer une partie (consomme un ticket) ou reprendre la partie en cours")
    public ResponseEntity<GameStartResponse> start(@AuthenticationPrincipal UserPrincipal player,
            @Valid @RequestBody StartGameDto request, HttpServletRequest http) {

        GameStartResponse response = gameService.start(player.getId(), request.quizId(),
                RequestContext.clientIp(http), RequestContext.userAgent(http));

        return ResponseEntity.status(response.resumed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/submit")
    @Operation(summary = "Partie BATCH : soumettre toutes les reponses, le score est recalcule par le serveur")
    public GameResultResponse submit(@AuthenticationPrincipal UserPrincipal player,
            @Valid @RequestBody SubmitGameDto request) {
        return gameService.submit(player.getId(), request);
    }

    @PostMapping("/{sessionId}/answer")
    @Operation(summary = "Partie SEQUENTIAL : repondre a la question en cours et recevoir la suivante")
    public GameTurnResponse answer(@AuthenticationPrincipal UserPrincipal player,
            @PathVariable Long sessionId, @Valid @RequestBody AnswerQuestionDto request) {
        return gameService.answer(player.getId(), sessionId, request);
    }

    @GetMapping("/solo/played-today")
    @Operation(summary = "Sujets deja joues en solo aujourd'hui (une partie par sujet et par jour)")
    public List<Long> soloSubjectsPlayedToday(@AuthenticationPrincipal UserPrincipal player) {
        return gameService.soloSubjectsPlayedToday(player.getId());
    }

    @GetMapping("/sessions")
    @Operation(summary = "Mes parties sur un quiz, avec mon meilleur score")
    public List<GameResultResponse> mySessions(@AuthenticationPrincipal UserPrincipal player,
            @RequestParam Long quizId) {
        return gameService.mySessions(player.getId(), quizId);
    }

    @GetMapping("/{sessionId}/review")
    @Operation(summary = "Corrige : juste, fausse ou sans reponse par question, sans enonce ni reponse "
            + "(disponible a la cloture du quiz)")
    public GameOutcomeResponse review(@AuthenticationPrincipal UserPrincipal player,
            @PathVariable Long sessionId) {
        return gameService.outcome(player.getId(), sessionId);
    }
}
