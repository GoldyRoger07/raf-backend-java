package com.raikiservice.raf.game.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Reponse d'un joueur, corrigee par le serveur. */
@Getter
@Setter
@Entity
@Table(name = "game_answers")
public class GameAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private GameSession session;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "choice_id", nullable = false)
    private Long choiceId;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    /** Temps ecoule depuis le debut de la partie, mesure par le client. */
    @Column(name = "elapsed_ms", nullable = false)
    private long elapsedMs;

    @Column(name = "points_awarded", nullable = false)
    private int pointsAwarded;
}
