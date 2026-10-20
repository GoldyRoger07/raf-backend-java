package com.raikiservice.raf.quiz.domain;

import java.time.Instant;

import com.raikiservice.raf.common.domain.AuditableEntity;
import com.raikiservice.raf.media.domain.MediaFile;
import com.raikiservice.raf.question.domain.Category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "quizzes")
public class Quiz extends AuditableEntity {

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, length = 180, unique = true)
    private String slug;

    @Column(length = 1000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "banner_media_id")
    private MediaFile banner;

    /** Theme general annonce avant le vote, pour que l'achat ne soit pas aveugle. */
    @Column(name = "theme_label", length = 160)
    private String themeLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuizStatus status = QuizStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_mode", nullable = false, length = 10)
    private GameMode gameMode = GameMode.GROUP;

    @Enumerated(EnumType.STRING)
    @Column(name = "topic_mode", nullable = false, length = 10)
    private TopicMode topicMode = TopicMode.ADMIN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "selected_category_id")
    private Category selectedCategory;

    /** GROUP : prix d'un ticket. SOLO : prix d'une partie, debite au lancement. */
    @Column(name = "ticket_price_credits", nullable = false)
    private int ticketPriceCredits;

    @Column(name = "min_players", nullable = false)
    private int minPlayers;

    @Column(name = "max_tickets_total")
    private Integer maxTicketsTotal;

    @Column(name = "max_tickets_per_player")
    private Integer maxTicketsPerPlayer;

    @Column(name = "start_delay_minutes", nullable = false)
    private int startDelayMinutes = 30;

    @Column(name = "threshold_reached_at")
    private Instant thresholdReachedAt;

    @Column(name = "scheduled_start_at")
    private Instant scheduledStartAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "play_mode", nullable = false, length = 20)
    private PlayMode playMode = PlayMode.SEQUENTIAL;

    /** Chrono de chaque question : la partie dure au plus N fois cette valeur. */
    @Column(name = "question_duration_seconds", nullable = false)
    private int questionDurationSeconds = 5;

    @Column(name = "questions_per_game", nullable = false)
    private int questionsPerGame = 40;

    @Column(name = "points_per_correct", nullable = false)
    private int pointsPerCorrect = 10;

    /** SOLO : prix verse pour un sans-faute. Null en GROUP. */
    @Column(name = "solo_prize_credits")
    private Long soloPrizeCredits;

    /** Premier joueur a le depasser : declenche le compte a rebours de cloture. */
    @Column(name = "min_score_threshold", nullable = false)
    private int minScoreThreshold;

    @Column(name = "closing_countdown_minutes", nullable = false)
    private int closingCountdownMinutes = 120;

    @Column(name = "closing_triggered_at")
    private Instant closingTriggeredAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "vote_opens_at")
    private Instant voteOpensAt;

    @Column(name = "vote_closes_at")
    private Instant voteClosesAt;

    @Column(name = "cancellation_reason", length = 255)
    private String cancellationReason;

    @Column(name = "created_by")
    private Long createdBy;

    public boolean isSolo() {
        return gameMode == GameMode.SOLO;
    }

    /**
     * Les tickets sont en vente pendant l'inscription et jusqu'a la cloture. Un
     * quiz solo n'en vend jamais : chaque partie est payee a son lancement.
     */
    public boolean acceptsTickets() {
        return gameMode == GameMode.GROUP && (status == QuizStatus.REGISTRATION
                || status == QuizStatus.SCHEDULED
                || status == QuizStatus.RUNNING
                || status == QuizStatus.CLOSING);
    }

    /** Une partie ne peut etre lancee que quand le quiz tourne. */
    public boolean acceptsPlay() {
        return status == QuizStatus.RUNNING || status == QuizStatus.CLOSING;
    }

    public boolean isVoteOpen(Instant now) {
        return topicMode == TopicMode.VOTE
                && selectedCategory == null
                && voteOpensAt != null && voteClosesAt != null
                && !now.isBefore(voteOpensAt) && now.isBefore(voteClosesAt);
    }

    /** Annulation possible tant qu'aucune partie n'a pu etre jouee. */
    public boolean isCancellable() {
        return status == QuizStatus.DRAFT
                || status == QuizStatus.REGISTRATION
                || status == QuizStatus.SCHEDULED;
    }
}
