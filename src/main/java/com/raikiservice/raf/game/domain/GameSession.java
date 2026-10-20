package com.raikiservice.raf.game.domain;

import java.time.Instant;
import java.time.LocalDate;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.domain.PlayMode;
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
 * Une partie : jouee avec un ticket en GROUP, payee a son lancement en SOLO.
 *
 * <p>{@code startedAt} fait autorite : il fixe l'echeance et sert au departage.
 * Le mode, le chrono et le bareme sont copies depuis le quiz au demarrage : la
 * partie se termine avec les regles sous lesquelles elle a commence, et la
 * correction n'a jamais a charger le quiz (voir {@code GameService#submit}).
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

    /** GROUP uniquement. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", unique = true)
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_mode", nullable = false, length = 10)
    private GameMode gameMode;

    /** Sujet joue, copie du quiz au lancement. */
    @Column(name = "category_id")
    private Long categoryId;

    /**
     * SOLO : jour civil (Haiti) du lancement. Avec le joueur et le sujet, il est
     * unique en base : une partie par sujet et par jour.
     */
    @Column(name = "solo_day")
    private LocalDate soloDay;

    /** SOLO : prix de la partie, et son debit sur le solde CREDIT. */
    @Column(name = "entry_price_credits")
    private Integer entryPriceCredits;

    @Column(name = "entry_transaction_id")
    private Long entryTransactionId;

    /** SOLO : prix d'un sans-faute en HTG, copie du quiz au lancement. */
    @Column(name = "prize_htg")
    private Long prizeHtg;

    /** SOLO : sans-faute realise, prix verse ({@code prizeTransactionId}). */
    @Column(nullable = false)
    private boolean won;

    @Column(name = "prize_transaction_id")
    private Long prizeTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GameSessionStatus status = GameSessionStatus.IN_PROGRESS;

    @Enumerated(EnumType.STRING)
    @Column(name = "play_mode", nullable = false, length = 20)
    private PlayMode playMode;

    /** Duree nominale : nombre de questions x chrono par question. */
    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "question_duration_seconds", nullable = false)
    private int questionDurationSeconds;

    @Column(name = "points_per_correct", nullable = false)
    private int pointsPerCorrect;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /**
     * Au-dela, la partie est close. BATCH : started_at + duree + marge reseau.
     * SEQUENTIAL : started_at + N x (chrono + marge par question) + marge reseau.
     */
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

    /**
     * Fin de partie vue par le joueur, sans la marge reseau. En SEQUENTIAL, une
     * question servie plus tard qu'au plus tot (reprise apres coupure) decale la
     * fin reelle : c'est alors l'echeance serveur qui fait foi.
     */
    public Instant playEndsAt() {
        return startedAt.plusSeconds(durationSeconds);
    }

    public boolean isSolo() {
        return gameMode == GameMode.SOLO;
    }

    public long questionDurationMs() {
        return questionDurationSeconds * 1_000L;
    }

    public boolean isOpenAt(Instant now) {
        return status == GameSessionStatus.IN_PROGRESS && !now.isAfter(serverDeadlineAt);
    }
}
