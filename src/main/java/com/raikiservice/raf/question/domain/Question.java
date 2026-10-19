package com.raikiservice.raf.question.domain;

import java.util.ArrayList;
import java.util.List;

import com.raikiservice.raf.common.domain.AuditableEntity;
import com.raikiservice.raf.media.domain.MediaFile;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "questions")
public class Question extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    /** Empreinte de l'enonce normalise : porte l'unicite par categorie. */
    @Column(name = "text_hash", nullable = false, length = 64)
    private String textHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuestionType type = QuestionType.SINGLE_CHOICE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Difficulty difficulty = Difficulty.MEDIUM;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "media_id")
    private MediaFile media;

    @Column(length = 1000)
    private String explanation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuestionStatus status = QuestionStatus.DRAFT;

    @Column(nullable = false, length = 2)
    private String lang = "fr";

    /**
     * Statistiques d'usage, alimentees au lot 6.
     *
     * <p>Un taux de reussite anormalement eleve sur une question deja beaucoup
     * servie est le signal qu'elle circule entre joueurs.
     */
    @Column(name = "times_served", nullable = false)
    private int timesServed;

    @Column(name = "times_correct", nullable = false)
    private int timesCorrect;

    @Column(name = "created_by")
    private Long createdBy;

    @OrderBy("position asc")
    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<QuestionChoice> choices = new ArrayList<>();

    public void addChoice(QuestionChoice choice) {
        choice.setQuestion(this);
        choices.add(choice);
    }

    public QuestionChoice correctChoice() {
        return choices.stream()
                .filter(QuestionChoice::isCorrect)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Question " + getId() + " sans bonne reponse."));
    }

    /** Taux de reussite observe, ou -1 si la question n'a jamais ete servie. */
    public double successRate() {
        return timesServed == 0 ? -1 : (double) timesCorrect / timesServed;
    }
}
