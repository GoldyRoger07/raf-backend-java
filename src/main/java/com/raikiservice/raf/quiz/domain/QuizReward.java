package com.raikiservice.raf.quiz.domain;

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

/** Recompense d'une tranche de rangs, montant fixe en credits. */
@Getter
@Setter
@Entity
@Table(name = "quiz_rewards")
public class QuizReward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @Column(name = "rank_from", nullable = false)
    private int rankFrom;

    @Column(name = "rank_to", nullable = false)
    private int rankTo;

    /** Montant verse a chaque joueur de la tranche, en HTG. */
    @Column(name = "amount_htg", nullable = false)
    private long amountHtg;

    @Column(length = 120)
    private String label;

    public int places() {
        return rankTo - rankFrom + 1;
    }

    public long totalCost() {
        return (long) places() * amountHtg;
    }
}
