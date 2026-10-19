package com.raikiservice.raf.game.domain;

import java.time.Instant;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.Ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Une partie jouee avec un ticket.
 *
 * <p>{@code startedAt} fait autorite : il fixe l'echeance et sert au departage.
 * La duree et le bareme sont copies depuis le quiz au demarrage, pour que le
 * submit n'ait jamais a charger le quiz (voir {@code GameService#submit}).
 */
@Getter
@Setter
@Entity
@Table(name = "game_sessions")
public class GameSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, unique = true)
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GameSessionStatus status = GameSessionStatus.IN_PROGRESS;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "points_per_correct", nullable = false)
    private int pointsPerCorrect;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** started_at + duree + marge reseau. */
    @Column(name = "server_deadline_at", nullable = false)
    private Instant serverDeadlineAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "client_duration_ms")
    private Long clientDurationMs;

    private Integer score;

    @Column(name = "correct_count")
    private Integer correctCount;

    @Column(name = "answered_count")
    private Integer answeredCount;

    @Column(name = "invalidation_reason", length = 255)
    private String invalidationReason;

    /** Signalement pour revue humaine : n'affecte pas le score. */
    @Column(nullable = false)
    private boolean flagged;

    @Column(name = "flag_reason", length = 255)
    private String flagReason;

    @Column(length = 45)
    private String ip;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    /** Fin du chrono vue par le joueur, sans la marge reseau. */
    public Instant playEndsAt() {
        return startedAt.plusSeconds(durationSeconds);
    }

    public boolean isOpenAt(Instant now) {
        return status == GameSessionStatus.IN_PROGRESS && !now.isAfter(serverDeadlineAt);
    }
}
