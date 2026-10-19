package com.raikiservice.raf.quiz.domain;

import com.raikiservice.raf.question.domain.Question;

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
 * Vivier fige au demarrage du quiz.
 *
 * <p>Les tirages des joueurs puisent ici et non dans la categorie : une question
 * publiee ou archivee en cours de quiz ne doit pas changer les regles pour les
 * joueurs suivants.
 */
@Getter
@Setter
@Entity
@Table(name = "quiz_question_pool")
public class QuizQuestionPool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;
}
