package com.raikiservice.raf.question.domain;

import com.raikiservice.raf.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Une demande de generation par IA, reussie ou non.
 *
 * <p>Sert au plafond journalier (un appel coute meme s'il ne produit rien) et a
 * comparer les modeles : le nombre de questions rejetees ou en double dit
 * lequel ecrit le mieux pour un sujet.
 */
@Getter
@Setter
@Entity
@Table(name = "ai_generation_runs")
public class AiGenerationRun extends BaseEntity {

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(nullable = false)
    private int requested;

    @Column(name = "created_count", nullable = false)
    private int createdCount;

    @Column(nullable = false)
    private int duplicates;

    @Column(nullable = false)
    private int rejected;

    @Column(length = 500)
    private String error;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;
}
