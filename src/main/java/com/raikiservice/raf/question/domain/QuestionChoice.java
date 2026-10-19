package com.raikiservice.raf.question.domain;

import com.raikiservice.raf.media.domain.MediaFile;

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

/** Une proposition de reponse. Son drapeau isCorrect ne quitte jamais le serveur. */
@Getter
@Setter
@Entity
@Table(name = "question_choices")
public class QuestionChoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(nullable = false, length = 500)
    private String text;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    @Column(nullable = false)
    private int position;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "media_id")
    private MediaFile media;
}
