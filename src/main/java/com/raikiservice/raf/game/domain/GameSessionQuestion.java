package com.raikiservice.raf.game.domain;

import java.time.Instant;

import com.raikiservice.raf.question.domain.Question;

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

/**
 * Une question du tirage fige d'une partie.
 *
 * <p>{@code correctChoiceId} est un instantane pris au demarrage : une
 * correction editoriale ulterieure ne modifie pas un score deja calcule.
 */
@Getter
@Setter
@Entity
@Table(name = "game_session_questions")
public class GameSessionQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private GameSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    /** Ordre impose : pas de saut, la question n+1 suit forcement la question n. */
    @Column(nullable = false)
    private int position;

    @Column(name = "correct_choice_id", nullable = false)
    private Long correctChoiceId;

    /**
     * SEQUENTIAL : envoi de la question au joueur, depart de son chrono. Null
     * tant qu'elle n'a pas ete servie, et toujours null en BATCH.
     */
    @Column(name = "served_at")
    private Instant servedAt;
}
