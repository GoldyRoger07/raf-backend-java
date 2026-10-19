package com.raikiservice.raf.game.domain;

import java.time.Instant;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.domain.BaseEntity;
import com.raikiservice.raf.quiz.domain.Quiz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Ligne du classement final, figee a la cloture du quiz. */
@Getter
@Setter
@Entity
@Table(name = "quiz_rankings")
public class QuizRanking extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "best_session_id", nullable = false)
    private Long bestSessionId;

    @Column(nullable = false)
    private int score;

    /** Debut de la partie qui a produit le meilleur score : critere de departage. */
    @Column(name = "first_started_at", nullable = false)
    private Instant firstStartedAt;

    @Column(name = "rank_position", nullable = false)
    private int rank;

    @Column(name = "reward_credits", nullable = false)
    private long rewardCredits;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "transaction_id")
    private Long transactionId;
}
